package mdt.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mdt.model.InvalidResourceStatusException;
import mdt.model.ModelValidationException;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.expr.MDTParserException;
import mdt.model.instance.MDTInstanceStatus;


/**
 * 예외 → HTTP 상태 규칙({@link AbstractMDTExceptionAdvice})을 MockMvc로 확인한다(mdt-manager 검토 D15·D16·D17).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTExceptionAdviceTest {
	private MockMvc m_mvc;

	@RestController
	static class ThrowingController {
		@GetMapping("/throw/{kind}")
		public String throwIt(@PathVariable("kind") String kind) throws Exception {
			throw switch ( kind ) {
				case "notfound" -> new ResourceNotFoundException("MDTInstance", "id=x");
				case "exists" -> new ResourceAlreadyExistsException("MDTInstance", "id=x");
				case "status" -> new InvalidResourceStatusException("MDTInstance", "x", MDTInstanceStatus.RUNNING);
				case "illegal" -> new IllegalArgumentException("bad");
				case "model" -> new ModelValidationException("bad");
				case "parser" -> new MDTParserException("bad expr");
				case "json" -> new IOException("Failed to parse JSON", new DeserializationException("bad"));
				case "denied" -> new AccessDeniedException("/x");
				case "timeout" -> new ExecutionException(new TimeoutException("slow"));
				case "unsupported" -> new UnsupportedOperationException("no");
				default -> new IllegalStateException("boom");
			};
		}

		@GetMapping("/param")
		public String needsParam(@RequestParam("ref") String ref) {
			return ref;
		}
	}

	@BeforeEach
	public void setUp() {
		m_mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
								.setControllerAdvice(new MDTExceptionAdvice())
								.build();
	}

	@Test
	public void mdtExceptionsMapToOneStatusTable() throws Exception {
		expect("notfound", 404);
		expect("exists", 409);
		expect("status", 409);
		expect("illegal", 400);
		expect("model", 400);
		expect("parser", 400);
		expect("json", 400);
		expect("denied", 403);
		expect("timeout", 408);		// ExecutionException은 원인으로 판단한다
		expect("unsupported", 501);
		expect("other", 500);
	}

	@Test
	public void registryBodyKeepsTheExceptionClassName() throws Exception {
		// HttpRegistryClient가 code의 클래스 이름으로 예외를 되살린다.
		m_mvc.perform(get("/throw/notfound"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ResourceNotFoundException.class.getName()));
	}

	@Test
	public void springStandardExceptionsKeepTheir4xxStatus() throws Exception {
		m_mvc.perform(get("/param")).andExpect(status().isBadRequest());				// 파라미터 누락
		m_mvc.perform(post("/param").param("ref", "x")).andExpect(status().isMethodNotAllowed());
	}

	@Test
	public void instanceManagerBodyIsRESTfulErrorEntity() {
		Object body = new MDTInstanceManagerExceptionAdvice().toErrorBody(new ResourceNotFoundException("MDTInstance", "id=x"));
		org.junit.jupiter.api.Assertions.assertEquals(ResourceNotFoundException.class.getName(),
													((utils.http.RESTfulErrorEntity)body).getCode());
	}

	/**
	 * instance-manager 컨트롤러에는 범위가 지정된 advice가 먼저 적용되어 RESTfulErrorEntity({@code message})로
	 * 응답하고, 다른 컨트롤러는 Fa3stMessage({@code text})로 응답한다.
	 */
	@Test
	public void scopedAdviceAppliesOnlyToInstanceManagerController() throws Exception {
		// 매니저를 주입하지 않은 컨트롤러: 조회가 NPE로 실패해 500이 된다.
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new MDTInstanceManagerController(), new ThrowingController())
									.setControllerAdvice(new MDTExceptionAdvice(), new MDTInstanceManagerExceptionAdvice())
									.build();
		mvc.perform(get("/instance-manager/instances/x"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value(NullPointerException.class.getName()))
			.andExpect(jsonPath("$.message").exists());
		mvc.perform(get("/throw/notfound"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.text").exists());
	}

	private void expect(String kind, int code) throws Exception {
		m_mvc.perform(get("/throw/" + kind)).andExpect(status().is(code));
	}
}
