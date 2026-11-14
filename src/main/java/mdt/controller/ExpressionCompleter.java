package mdt.controller;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.digitaltwin.aas4j.v3.model.Operation;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;

import utils.Preconditions;

import mdt.instance.AbstractJpaInstanceManager.SubmodelKey;
import mdt.model.ResourceNotFoundException;
import mdt.model.SubmodelService;


/**
 * MDT 표현식(문법: mdt-client의 {@code MdtExpr.g4})의 앞부분에 대한 자동 완성 후보를 만든다.
 * <p>
 * 다루는 형태는 다음과 같다. {@code <sm>}은 {@code <instance>:[idShort=]<submodelIdShort>} 또는
 * {@code submodel:id=<submodelId>}이다.
 * <ul>
 *   <li>{@code param:<instance>:<parameterId>}</li>
 *   <li>{@code oparg:<sm>:(in|out):<argumentId>} — {@code <sm>}은 연산(AI/Simulation) Submodel</li>
 *   <li>{@code opvar:<sm>:<idShortPath>:(in|out|inout):<ordinal>} — 경로는 Operation element</li>
 *   <li>{@code timeseries:<sm>} — {@code <sm>}은 시계열 Submodel. {@code #}·{@code |} 뒤(범위·컬럼)는 완성하지 않는다.</li>
 *   <li>{@code <sm>:<idShortPath>}</li>
 * </ul>
 * 문법이 허용하는 와일드카드 {@code *}(파라미터·인자 전체)와 정수 인덱스는 후보로 내지 않는다.
 * <p>
 * <b>후보 형식:</b> 후보는 입력한 앞부분 전체로 시작하는 완전한 표현식이다. 다음 단계가 있는 후보는 구분자로
 * 끝난다(이름 뒤 {@code :}, SubmodelElementCollection 뒤 {@code .}, SubmodelElementList 뒤 {@code [}). 문법의
 * 식별자({@code ID})로 쓸 수 없는 이름은 따옴표로 감싼다.
 * <p>
 * <b>인스턴스 상태:</b> 식별자(인스턴스·파라미터·연산·인자·Submodel·시계열)는 등록 정보로 만들므로 실행 중이
 * 아닌 MDTInstance도 후보가 된다. element 경로({@code <idShortPath>})는 실행 중인 MDTInstance에서만 만들 수
 * 있고, 그렇지 않으면 빈 목록이다.
 * <p>
 * <b>모호성:</b> 라벨({@code param}, {@code oparg}, {@code opvar}, {@code timeseries}, {@code submodel})과 같은
 * 이름의 MDTInstance가 있으면 라벨로 해석한다.
 * <p>
 * 입력 중인 앞부분은 대부분 미완성이므로 예외를 던지지 않고, 해석할 수 없으면 빈 목록을 반환한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class ExpressionCompleter {
	/** 반환하는 후보의 최대 개수. */
	static final int MAX_CANDIDATES = 500;

	static final List<String> LABELS = List.of("param", "oparg", "opvar", "timeseries", "submodel");
	private static final List<String> ARG_KINDS = List.of("in", "out");
	private static final List<String> VAR_KINDS = List.of("in", "out", "inout");

	// 문법의 ID 토큰. 이 형태가 아니거나 다른 토큰(NULL, BOOLEAN, ISO8601_DURATION)으로 읽히는 이름은 따옴표로 감싼다.
	private static final Pattern ID = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_-]*");
	private static final Pattern NON_ID_KEYWORD = Pattern.compile("null|true|false|P[0-9]+D.*|PT.*");

	/**
	 * 후보를 만드는 데 필요한 MDT 정보 조회.
	 */
	interface Source {
		List<String> instanceIds();
		List<String> parameterIds(String instanceId);
		/** 연산(AI/Simulation) Submodel의 idShort 목록. 연산 식별자와 같다. */
		List<String> operationSubmodels(String instanceId);
		/** 연산 인자 식별자 목록. {@code kind}는 {@code in} 또는 {@code out}. */
		List<String> argumentIds(String instanceId, String operationSubmodel, String kind);
		List<String> submodelIdShorts(String instanceId);
		List<String> timeSeriesSubmodels(String instanceId);
		List<SubmodelKey> submodels();
		/** 실행 중인 MDTInstance의 Submodel 서비스. 실행 중이 아니거나 없으면 빈 값. */
		Optional<SubmodelService> runningSubmodelService(String instanceId, String submodelIdShort);
	}

	private enum SubmodelKind { ANY, OPERATION, TIMESERIES }

	private final Source m_source;

	ExpressionCompleter(Source source) {
		Preconditions.checkNotNullArgument(source, "source is null");
		m_source = source;
	}

	/**
	 * 주어진 앞부분에 대한 후보를 반환한다.
	 *
	 * @param prefix	입력 중인 표현식 앞부분. {@code null}이면 빈 문자열로 본다.
	 * @return	후보 목록(최대 {@link #MAX_CANDIDATES}개). 없으면 빈 목록.
	 */
	List<String> complete(String prefix) {
		String input = (prefix != null) ? prefix : "";
		Tokens tokens = Tokens.parse(input);
		if ( tokens.hasTail ) {
			return List.of();		// 시계열 범위(#)·컬럼(|) 이후는 완성하지 않는다.
		}

		List<String> values = valuesAtLast(tokens.list);
		String base = tokens.baseOfLast();
		Set<String> candidates = new LinkedHashSet<>();
		for ( String value: values ) {
			String candidate = base + value;
			if ( candidate.startsWith(input) ) {
				candidates.add(candidate);
				if ( candidates.size() >= MAX_CANDIDATES ) {
					break;
				}
			}
		}
		return List.copyOf(candidates);
	}

	// 마지막 토큰 자리에 올 수 있는 값들(앞 토큰들은 이미 정해졌다).
	private List<String> valuesAtLast(List<Token> toks) {
		int last = toks.size() - 1;
		if ( last == 0 ) {
			List<String> out = new ArrayList<>();
			LABELS.forEach(label -> out.add(label + ":"));
			m_source.instanceIds().forEach(id -> out.add(quote(id) + ":"));
			return out;
		}

		return switch ( toks.get(0).value ) {
			case "param" -> paramValues(toks);
			case "oparg" -> opargValues(toks);
			case "opvar" -> opvarValues(toks);
			case "timeseries" -> timeseriesValues(toks);
			default -> elementValues(toks);
		};
	}

	// param:<instance>:<parameterId>
	private List<String> paramValues(List<Token> toks) {
		int last = toks.size() - 1;
		List<String> out = new ArrayList<>();
		if ( last == 1 ) {
			m_source.instanceIds().forEach(id -> out.add(quote(id) + ":"));
		}
		else if ( last == 2 ) {
			m_source.parameterIds(toks.get(1).value).forEach(id -> out.add(quote(id)));
		}
		return out;
	}

	// oparg:<sm>:(in|out):<argumentId>
	private List<String> opargValues(List<Token> toks) {
		List<String> out = new ArrayList<>();
		SubmodelRef sm = resolveSubmodel(toks, 1, SubmodelKind.OPERATION, ":", out);
		if ( sm == null ) {
			return out;
		}

		int last = toks.size() - 1;
		if ( last == sm.next ) {
			ARG_KINDS.forEach(kind -> out.add(kind + ":"));
		}
		else if ( last == sm.next + 1 ) {
			String kind = toks.get(sm.next).value;
			if ( ARG_KINDS.contains(kind) ) {
				m_source.argumentIds(sm.instanceId, sm.idShort, kind).forEach(id -> out.add(quote(id)));
			}
		}
		return out;
	}

	// opvar:<sm>:<idShortPath>:(in|out|inout):<ordinal>
	private List<String> opvarValues(List<Token> toks) {
		List<String> out = new ArrayList<>();
		SubmodelRef sm = resolveSubmodel(toks, 1, SubmodelKind.ANY, ":", out);
		if ( sm == null ) {
			return out;
		}

		int last = toks.size() - 1;
		Optional<SubmodelService> svc = m_source.runningSubmodelService(sm.instanceId, sm.idShort);
		if ( svc.isEmpty() ) {
			return out;
		}
		if ( last == sm.next ) {
			return SubmodelElementCandidates.list(svc.get(), toks.get(last).value, ":");
		}

		Operation op = findOperation(svc.get(), toks.get(sm.next).value);
		if ( op == null ) {
			return out;
		}
		if ( last == sm.next + 1 ) {
			for ( String kind: VAR_KINDS ) {
				if ( variableCount(op, kind) > 0 ) {
					out.add(kind + ":");
				}
			}
		}
		else if ( last == sm.next + 2 ) {
			int count = variableCount(op, toks.get(sm.next + 1).value);
			for ( int i = 0; i < count; ++i ) {
				out.add(Integer.toString(i));
			}
		}
		return out;
	}

	// timeseries:<sm> (범위·컬럼은 Tokens가 걸러 낸다)
	private List<String> timeseriesValues(List<Token> toks) {
		List<String> out = new ArrayList<>();
		SubmodelRef sm = resolveSubmodel(toks, 1, SubmodelKind.TIMESERIES, "", out);
		return (sm == null) ? out : List.of();		// Submodel 뒤에는 ':'로 이어지는 부분이 없다.
	}

	// <sm>:<idShortPath>
	private List<String> elementValues(List<Token> toks) {
		List<String> out = new ArrayList<>();
		SubmodelRef sm = resolveSubmodel(toks, 0, SubmodelKind.ANY, ":", out);
		if ( sm == null || toks.size() - 1 != sm.next ) {
			return out;
		}
		return m_source.runningSubmodelService(sm.instanceId, sm.idShort)
						.map(svc -> SubmodelElementCandidates.list(svc, toks.get(sm.next).value, null))
						.orElse(List.of());
	}

	private record SubmodelRef(String instanceId, String idShort, int next) { }

	/**
	 * {@code toks[i]}부터 시작하는 Submodel 지정({@code <instance>:[idShort=]<idShort>} 또는
	 * {@code submodel:id=<id>})을 해석한다. 아직 입력 중이면 마지막 자리의 후보를 {@code out}에 넣고
	 * {@code null}을 반환한다.
	 */
	private SubmodelRef resolveSubmodel(List<Token> toks, int i, SubmodelKind kind, String suffix,
										List<String> out) {
		int last = toks.size() - 1;
		if ( last == i ) {
			out.add("submodel:");
			m_source.instanceIds().forEach(id -> out.add(quote(id) + ":"));
			return null;
		}

		String head = toks.get(i).value;
		Token second = toks.get(i + 1);
		if ( head.equals("submodel") ) {
			if ( last == i + 1 ) {
				for ( SubmodelKey key: m_source.submodels() ) {
					if ( matches(key.instanceId(), key.idShort(), kind) ) {
						out.add("id=" + quote(key.submodelId()) + suffix);
					}
				}
				return null;
			}
			if ( !second.value.startsWith("id=") ) {
				return null;
			}
			String id = second.value.substring("id=".length());
			return m_source.submodels().stream()
							.filter(key -> key.submodelId().equals(id))
							.findFirst()
							.map(key -> new SubmodelRef(key.instanceId(), key.idShort(), i + 2))
							.orElse(null);
		}

		if ( last == i + 1 ) {
			String keyPrefix = second.raw.startsWith("idShort=") ? "idShort=" : "";
			submodelsOf(head, kind).forEach(sm -> out.add(keyPrefix + quote(sm) + suffix));
			return null;
		}
		String idShort = second.value.startsWith("idShort=") ? second.value.substring("idShort=".length())
															: second.value;
		return new SubmodelRef(head, idShort, i + 2);
	}

	private List<String> submodelsOf(String instanceId, SubmodelKind kind) {
		return switch ( kind ) {
			case OPERATION -> m_source.operationSubmodels(instanceId);
			case TIMESERIES -> m_source.timeSeriesSubmodels(instanceId);
			default -> m_source.submodelIdShorts(instanceId);
		};
	}

	private boolean matches(String instanceId, String idShort, SubmodelKind kind) {
		return kind == SubmodelKind.ANY || submodelsOf(instanceId, kind).contains(idShort);
	}

	private static Operation findOperation(SubmodelService svc, String path) {
		try {
			SubmodelElement sme = svc.getSubmodelElementByPath(path);
			return (sme instanceof Operation op) ? op : null;
		}
		catch ( ResourceNotFoundException e ) {
			return null;
		}
	}

	private static int variableCount(Operation op, String kind) {
		var vars = switch ( kind ) {
			case "in" -> op.getInputVariables();
			case "out" -> op.getOutputVariables();
			case "inout" -> op.getInoutputVariables();
			default -> null;
		};
		return (vars != null) ? vars.size() : 0;
	}

	/**
	 * 이름을 표현식에 쓸 수 있는 형태로 만든다. 문법의 식별자로 읽히지 않는 이름은 따옴표로 감싼다.
	 */
	static String quote(String name) {
		if ( ID.matcher(name).matches() && !NON_ID_KEYWORD.matcher(name).matches() ) {
			return name;
		}
		return name.contains("\"") ? "'" + name + "'" : "\"" + name + "\"";
	}

	private record Token(String raw, String value) { }

	/**
	 * 입력을 {@code :}로 나눈 토큰. 따옴표 안의 {@code :}는 나누지 않고, 따옴표 문자는 {@link Token#value}에서
	 * 뺀다. 따옴표 밖의 {@code #}나 {@code |}를 만나면 그 뒤는 시계열 범위·컬럼이므로 {@link #hasTail}을 켠다.
	 */
	private static final class Tokens {
		private final List<Token> list = new ArrayList<>();
		private boolean hasTail = false;

		static Tokens parse(String input) {
			Tokens tokens = new Tokens();
			StringBuilder raw = new StringBuilder();
			StringBuilder value = new StringBuilder();
			char quote = 0;
			for ( int i = 0; i < input.length(); ++i ) {
				char c = input.charAt(i);
				if ( quote != 0 ) {
					raw.append(c);
					if ( c == quote ) {
						quote = 0;
					}
					else {
						value.append(c);
					}
				}
				else if ( c == '"' || c == '\'' ) {
					raw.append(c);
					quote = c;
				}
				else if ( c == ':' ) {
					tokens.list.add(new Token(raw.toString(), value.toString()));
					raw.setLength(0);
					value.setLength(0);
				}
				else if ( c == '#' || c == '|' ) {
					tokens.hasTail = true;
					break;
				}
				else {
					raw.append(c);
					value.append(c);
				}
			}
			tokens.list.add(new Token(raw.toString(), value.toString()));
			return tokens;
		}

		// 마지막 토큰 앞까지의 원문(각 토큰 뒤에 ':').
		String baseOfLast() {
			StringBuilder base = new StringBuilder();
			for ( int i = 0; i < list.size() - 1; ++i ) {
				base.append(list.get(i).raw).append(':');
			}
			return base.toString();
		}
	}
}
