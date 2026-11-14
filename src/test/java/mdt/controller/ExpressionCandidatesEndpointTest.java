package mdt.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import mdt.instance.AbstractJpaInstanceManager;


/**
 * {@code GET /list/expr}: 접두어를 생략해도, 조회 중 오류가 나도 200과 후보 목록(비어 있을 수 있음)을 돌려준다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ExpressionCandidatesEndpointTest {
	private AbstractJpaInstanceManager<?> m_manager;
	private MockMvc m_mvc;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setUp() {
		m_manager = mock(AbstractJpaInstanceManager.class);
		MDTListCandidatesController ctrl = new MDTListCandidatesController();
		ReflectionTestUtils.setField(ctrl, "m_instanceManager", m_manager);
		m_mvc = MockMvcBuilders.standaloneSetup(ctrl).build();
	}

	@Test
	public void missingPrefixListsTopLevel() throws Exception {
		when(m_manager.listInstanceIds("all")).thenReturn(List.of("CNC"));
		String expected = String.join(System.lineSeparator(),
										"param:", "oparg:", "opvar:", "timeseries:", "submodel:", "CNC:");

		m_mvc.perform(get("/list/expr"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
			.andExpect(content().string(expected));
	}

	@Test
	public void lookupFailureIsAnEmptyList() throws Exception {
		when(m_manager.listParameterIds("CNC")).thenThrow(new IllegalStateException("db down"));

		m_mvc.perform(get("/list/expr").param("prefix", "param:CNC:"))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
	}
}
