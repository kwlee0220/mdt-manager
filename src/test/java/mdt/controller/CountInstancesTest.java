package mdt.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import mdt.instance.AbstractJpaInstanceManager;


/**
 * {@code GET /instance-manager/instances/$count}: mdt-client의 {@code countInstances()}가
 * {@code Long.parseLong}으로 읽는 숫자 본문을 돌려준다. {@code /instances/{id}}보다 먼저 매칭되어야 한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class CountInstancesTest {
	@Test
	@SuppressWarnings("rawtypes")
	public void countIsPlainNumber() throws Exception {
		AbstractJpaInstanceManager manager = mock(AbstractJpaInstanceManager.class);
		when(manager.countInstances()).thenReturn(7L);
		MDTInstanceManagerController ctrl = new MDTInstanceManagerController();
		ReflectionTestUtils.setField(ctrl, "m_instanceManager", manager);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(ctrl).build();

		mvc.perform(get("/instance-manager/instances/$count"))
			.andExpect(status().isOk())
			.andExpect(content().string("7"));
	}
}
