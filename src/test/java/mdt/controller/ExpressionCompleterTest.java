package mdt.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperation;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultOperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementList;
import org.junit.jupiter.api.Test;

import mdt.instance.AbstractJpaInstanceManager.SubmodelKey;
import mdt.model.SubmodelService;
import mdt.model.sm.SubmodelUtils;


/**
 * {@link ExpressionCompleter}가 MDT 표현식의 형태별로 다음 자리의 후보를 만드는지 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ExpressionCompleterTest {
	// 인스턴스: CNC(실행 중), Idle(정지). CNC의 Submodel: Data, AI(연산), Sim(연산), Hist(시계열)
	private static final Submodel DATA = new DefaultSubmodel.Builder()
		.id("urn:mdt:CNC:Data").idShort("Data")
		.submodelElements(List.of(
			prop("Temp"),
			new DefaultSubmodelElementCollection.Builder().idShort("Status")
					.value(List.<SubmodelElement>of(prop("Mode"), prop("Speed"))).build(),
			new DefaultSubmodelElementList.Builder().idShort("Tools")
					.value(List.<SubmodelElement>of(prop("t0"), prop("t1"))).build(),
			new DefaultOperation.Builder().idShort("Run")
					.inputVariables(List.of(var("a"), var("b")))
					.outputVariables(List.of(var("r")))
					.build()))
		.build();

	private static final ExpressionCompleter COMPLETER = new ExpressionCompleter(new FakeSource());

	private static List<String> complete(String prefix) {
		return COMPLETER.complete(prefix);
	}

	@Test
	public void top_level_offers_labels_and_instances() {
		assertEquals(List.of("param:", "oparg:", "opvar:", "timeseries:", "submodel:", "CNC:", "Idle:"),
					complete(""));
		assertEquals(List.of("param:"), complete("p"));
		assertEquals(List.of("CNC:"), complete("C"));
		assertEquals(List.of("oparg:", "opvar:"), complete("op"));
	}

	@Test
	public void param() {
		assertEquals(List.of("param:CNC:", "param:Idle:"), complete("param:"));
		assertEquals(List.of("param:CNC:speed", "param:CNC:temp"), complete("param:CNC:"));
		assertEquals(List.of("param:CNC:temp"), complete("param:CNC:t"));
		assertEquals(List.of(), complete("param:CNC:temp:"));
	}

	@Test
	public void oparg_goes_through_submodel_kind_and_argument() {
		assertEquals(List.of("oparg:CNC:AI:", "oparg:CNC:Sim:"), complete("oparg:CNC:"));
		assertEquals(List.of("oparg:CNC:AI:in:", "oparg:CNC:AI:out:"), complete("oparg:CNC:AI:"));
		assertEquals(List.of("oparg:CNC:AI:in:Image", "oparg:CNC:AI:in:Threshold"),
					complete("oparg:CNC:AI:in:"));
		assertEquals(List.of("oparg:CNC:AI:out:Defect"), complete("oparg:CNC:AI:out:D"));
		assertEquals(List.of(), complete("oparg:CNC:AI:inout:"));
	}

	@Test
	public void submodel_by_id_needs_quotes_and_resolves() {
		assertEquals(List.of("oparg:submodel:id=\"urn:mdt:CNC:AI\":"), complete("oparg:submodel:id="));
		String aiById = "oparg:submodel:id=\"urn:mdt:CNC:AI\":";
		assertEquals(List.of(aiById + "in:", aiById + "out:"),
					complete("oparg:submodel:id=\"urn:mdt:CNC:AI\":"));
		assertTrue(complete("submodel:id=").contains("submodel:id=\"urn:mdt:CNC:Data\":"));
		String dataById = "submodel:id=\"urn:mdt:CNC:Data\":";
		assertEquals(List.of(dataById + "Temp"), complete(dataById + "Te"));
	}

	@Test
	public void element_paths() {
		assertEquals(List.of("CNC:AI:", "CNC:Data:", "CNC:Hist:", "CNC:Sim:"), complete("CNC:"));
		assertEquals(List.of("CNC:idShort=Data:"), complete("CNC:idShort=D"));
		assertEquals(List.of("CNC:Data:Temp", "CNC:Data:Status", "CNC:Data:Status.", "CNC:Data:Tools",
							"CNC:Data:Tools[", "CNC:Data:Run"), complete("CNC:Data:"));
		assertEquals(List.of("CNC:Data:Status.Mode"), complete("CNC:Data:Status.M"));
		assertEquals(List.of("CNC:Data:Tools[0]", "CNC:Data:Tools[1]"), complete("CNC:Data:Tools["));
		assertEquals(List.of(), complete("CNC:Data:Tools[x"));
		assertEquals(List.of(), complete("CNC:Data:Nope."));
		// 실행 중이 아닌 인스턴스는 element 경로를 만들지 않는다.
		assertEquals(List.of(), complete("Idle:Data:"));
	}

	@Test
	public void opvar_goes_through_operation_kind_and_ordinal() {
		assertEquals(List.of("opvar:CNC:Data:Run", "opvar:CNC:Data:Run:"), complete("opvar:CNC:Data:R"));
		assertEquals(List.of("opvar:CNC:Data:Run:in:", "opvar:CNC:Data:Run:out:"),
					complete("opvar:CNC:Data:Run:"));
		assertEquals(List.of("opvar:CNC:Data:Run:in:0", "opvar:CNC:Data:Run:in:1"),
					complete("opvar:CNC:Data:Run:in:"));
		assertEquals(List.of(), complete("opvar:CNC:Data:Temp:"));
	}

	@Test
	public void timeseries_lists_only_timeseries_submodels_and_stops_at_range() {
		assertEquals(List.of("timeseries:CNC:Hist"), complete("timeseries:CNC:"));
		assertEquals(List.of(), complete("timeseries:CNC:Hist:"));
		assertEquals(List.of(), complete("timeseries:CNC:Hist#last=5"));
	}

	@Test
	public void quoting_and_tolerance() {
		assertEquals("abc", ExpressionCompleter.quote("abc"));
		assertEquals("\"a:b\"", ExpressionCompleter.quote("a:b"));
		assertEquals("\"123\"", ExpressionCompleter.quote("123"));
		assertEquals("\"true\"", ExpressionCompleter.quote("true"));
		assertEquals("\"P1D\"", ExpressionCompleter.quote("P1D"));
		assertEquals(complete(""), complete(null));
		assertEquals(List.of(), complete("Unknown:Data:"));
		assertEquals(List.of(), complete("param:Unknown:x"));
	}

	private static DefaultProperty prop(String idShort) {
		return new DefaultProperty.Builder().idShort(idShort).build();
	}

	private static DefaultOperationVariable var(String idShort) {
		return new DefaultOperationVariable.Builder().value(prop(idShort)).build();
	}

	private static SubmodelService service(Submodel submodel) {
		SubmodelService svc = mock(SubmodelService.class);
		when(svc.getSubmodel()).thenReturn(submodel);
		when(svc.getSubmodelElementByPath(anyString()))
			.thenAnswer(inv -> SubmodelUtils.traverse(submodel, (String)inv.getArgument(0)));
		return svc;
	}

	private static final class FakeSource implements ExpressionCompleter.Source {
		private final SubmodelService m_data = service(DATA);

		@Override
		public List<String> instanceIds() {
			return List.of("CNC", "Idle");
		}

		@Override
		public List<String> parameterIds(String instanceId) {
			return instanceId.equals("CNC") ? List.of("speed", "temp") : List.of();
		}

		@Override
		public List<String> operationSubmodels(String instanceId) {
			return instanceId.equals("CNC") ? List.of("AI", "Sim") : List.of();
		}

		@Override
		public List<String> argumentIds(String instanceId, String operationSubmodel, String kind) {
			Map<String,List<String>> args = Map.of("in", List.of("Image", "Threshold"),
													"out", List.of("Defect"));
			return (instanceId.equals("CNC") && operationSubmodel.equals("AI")) ? args.get(kind) : List.of();
		}

		@Override
		public List<String> submodelIdShorts(String instanceId) {
			return instanceId.equals("CNC") ? List.of("AI", "Data", "Hist", "Sim") : List.of();
		}

		@Override
		public List<String> timeSeriesSubmodels(String instanceId) {
			return instanceId.equals("CNC") ? List.of("Hist") : List.of();
		}

		@Override
		public List<SubmodelKey> submodels() {
			return List.of(new SubmodelKey("CNC", "urn:mdt:CNC:Data", "Data"),
							new SubmodelKey("CNC", "urn:mdt:CNC:AI", "AI"));
		}

		@Override
		public Optional<SubmodelService> runningSubmodelService(String instanceId, String submodelIdShort) {
			return (instanceId.equals("CNC") && submodelIdShort.equals("Data")) ? Optional.of(m_data)
																				: Optional.empty();
		}
	}
}
