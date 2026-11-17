package mdt.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import utils.func.FOption;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.model.ResourceNotFoundException;


/**
 * 참조 핸들러에서 난 예외가 advice 하나에서 응답으로 바뀌는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ReferenceErrorResponseTest {
	@Test
	public void handlerFailureGoesToTheAdvice() throws Exception {
		AbstractJpaInstanceManager manager = mock(AbstractJpaInstanceManager.class);
		JpaInstance inst = mock(JpaInstance.class);
		when(manager.getInstance("t1")).thenReturn(inst);
		when(inst.getSubmodelServiceByIdShort("Data")).thenReturn(FOption.empty());

		MDTInstanceManagerController ctrl = new MDTInstanceManagerController();
		ReflectionTestUtils.setField(ctrl, "m_instanceManager", manager);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(ctrl)
									.setControllerAdvice(new MDTInstanceManagerExceptionAdvice())
									.build();

		mvc.perform(get("/instance-manager/submodel-element").param("ref", "t1:Data"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ResourceNotFoundException.class.getName()));
	}
}
