package mdt.controller;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Operation;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;

import mdt.model.ResourceNotFoundException;
import mdt.model.SubmodelService;


/**
 * Submodel 안의 SubmodelElement idShort 경로 자동 완성 후보를 만든다.
 * <p>
 * 입력 경로는 마지막 구분자({@code .} 또는 {@code [})를 기준으로 부모 경로와 입력 중인 조각으로 나눈다.
 * <ul>
 *   <li>{@code ""} 또는 {@code "Da"}: Submodel 최상위 element 중 이름이 조각으로 시작하는 것.</li>
 *   <li>{@code "Data."} / {@code "Data.Te"}: {@code Data}(SubmodelElementCollection)의 하위 element.</li>
 *   <li>{@code "List["} / {@code "List[1"}: {@code List}(SubmodelElementList)의 인덱스.</li>
 * </ul>
 * 후보는 전체 경로이며, 하위 element를 더 지정할 수 있는 후보는 다음 구분자를 붙인 형태도 함께 낸다
 * (SubmodelElementCollection은 {@code .}, SubmodelElementList는 {@code [}). 부모 경로가 없거나 하위 element를
 * 갖지 않는 element이면 빈 목록이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class SubmodelElementCandidates {
	private SubmodelElementCandidates() {
		throw new AssertionError("Should not be called: class=" + SubmodelElementCandidates.class.getName());
	}

	/**
	 * 주어진 경로 앞부분에 대한 element 경로 후보를 반환한다.
	 *
	 * @param svc				대상 Submodel 서비스.
	 * @param path				입력 중인 idShort 경로. {@code null}이면 빈 문자열로 본다.
	 * @param operationSuffix	{@link Operation} element 후보에 덧붙일 문자열. {@code null}이면 덧붙인 형태를 내지 않는다.
	 * 							(연산 변수 표현식 {@code opvar:...:<경로>:}에서 {@code ":"}를 쓴다.)
	 * @return	경로 후보 목록.
	 */
	static List<String> list(SubmodelService svc, String path, String operationSuffix) {
		if ( path == null ) {
			path = "";
		}

		int sepIdx = Math.max(path.lastIndexOf('.'), path.lastIndexOf('['));
		String parentPath = (sepIdx >= 0) ? path.substring(0, sepIdx) : "";
		char sep = (sepIdx >= 0) ? path.charAt(sepIdx) : '.';
		String partial = path.substring(sepIdx + 1);

		List<String> candidates = new ArrayList<>();
		if ( parentPath.isEmpty() ) {
			if ( sepIdx >= 0 ) {
				return candidates;		// ".x" 또는 "[x"처럼 부모 없는 구분자
			}
			for ( SubmodelElement child: svc.getSubmodel().getSubmodelElements() ) {
				addNamed(candidates, "", child, partial, operationSuffix);
			}
			return candidates;
		}

		SubmodelElement parent;
		try {
			parent = svc.getSubmodelElementByPath(parentPath);
		}
		catch ( ResourceNotFoundException e ) {
			return candidates;
		}
		if ( sep == '.' && parent instanceof SubmodelElementCollection smc ) {
			for ( SubmodelElement child: smc.getValue() ) {
				addNamed(candidates, parentPath + ".", child, partial, operationSuffix);
			}
		}
		else if ( sep == '[' && parent instanceof SubmodelElementList sml ) {
			List<SubmodelElement> items = sml.getValue();
			for ( int i = 0; i < items.size(); ++i ) {
				String index = Integer.toString(i);
				if ( index.startsWith(partial) ) {
					String itemPath = parentPath + "[" + index + "]";
					addWithSuffixes(candidates, itemPath, items.get(i), operationSuffix);
				}
			}
		}
		return candidates;
	}

	private static void addNamed(List<String> candidates, String base, SubmodelElement child, String partial,
									String operationSuffix) {
		String idShort = child.getIdShort();
		if ( idShort != null && idShort.startsWith(partial) ) {
			addWithSuffixes(candidates, base + idShort, child, operationSuffix);
		}
	}

	private static void addWithSuffixes(List<String> candidates, String path, SubmodelElement sme,
										String operationSuffix) {
		candidates.add(path);
		if ( sme instanceof SubmodelElementCollection ) {
			candidates.add(path + ".");
		}
		else if ( sme instanceof SubmodelElementList ) {
			candidates.add(path + "[");
		}
		else if ( sme instanceof Operation && operationSuffix != null ) {
			candidates.add(path + operationSuffix);
		}
	}
}
