package mdt.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaMDTSubmodelDescriptor;
import mdt.instance.jpa.JpaMDTSubmodelDescriptorRepository;
import mdt.model.ResourceNotFoundException;


/**
 * 서브모델 디스크립터 목록과 후보 조회가 고아 디스크립터와 그 밖의 오류를 구분하는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class SubmodelRegistryListTest {
	private AbstractJpaInstanceManager m_manager;
	private JpaMDTSubmodelDescriptorRepository m_repo;
	private MockMvc m_mvc;

	@BeforeEach
	public void setUp() {
		m_manager = mock(AbstractJpaInstanceManager.class);
		m_repo = mock(JpaMDTSubmodelDescriptorRepository.class);

		JpaSubmodelRegistryController regCtrl = new JpaSubmodelRegistryController(m_repo);
		ReflectionTestUtils.setField(regCtrl, "m_instanceManager", m_manager);
		MDTListCandidatesController listCtrl = new MDTListCandidatesController();
		ReflectionTestUtils.setField(listCtrl, "m_instanceManager", m_manager);
		m_mvc = MockMvcBuilders.standaloneSetup(regCtrl, listCtrl)
								.setControllerAdvice(new MDTExceptionAdvice())
								.build();
	}

	@Test
	public void instanceIsLookedUpOncePerInstance() throws Exception {
		JpaInstance inst = mock(JpaInstance.class);
		when(inst.getServiceEndpoint()).thenReturn("https://host:1/api/v3.0");
		when(m_manager.getInstance("t1")).thenReturn(inst);
		List<JpaMDTSubmodelDescriptor> descs = List.of(desc("sm1", instance("t1")), desc("sm2", instance("t1")));
		when(m_repo.findAll()).thenReturn(descs);

		String body = m_mvc.perform(get("/aas_registry/submodel-descriptors"))
							.andExpect(status().isOk())
							.andReturn().getResponse().getContentAsString();
		assertTrue(body.contains("https://host:1/api/v3.0"), body);
		verify(m_manager, times(1)).getInstance("t1");
	}

	@Test
	public void orphansStayInTheListWithoutEndpoint() throws Exception {
		when(m_manager.getInstance("gone")).thenThrow(new ResourceNotFoundException("MDTInstance", "id=gone"));
		List<JpaMDTSubmodelDescriptor> descs = List.of(desc("sm1", null), desc("sm2", instance("gone")));
		when(m_repo.findAll()).thenReturn(descs);

		String body = m_mvc.perform(get("/aas_registry/submodel-descriptors"))
							.andExpect(status().isOk())
							.andReturn().getResponse().getContentAsString();
		assertTrue(body.contains("sm1") && body.contains("sm2"), body);
		assertFalse(body.contains("https://"), body);
	}

	@Test
	public void otherFailuresAreNotHiddenInRegistryList() throws Exception {
		when(m_manager.getInstance("t1")).thenThrow(new IllegalStateException("db down"));
		List<JpaMDTSubmodelDescriptor> descs = List.of(desc("sm1", instance("t1")));
		when(m_repo.findAll()).thenReturn(descs);

		m_mvc.perform(get("/aas_registry/submodel-descriptors")).andExpect(status().isInternalServerError());
	}

	@Test
	public void candidateElementsAreEmptyOnlyForMissingInstance() throws Exception {
		when(m_manager.getInstance("gone")).thenThrow(new ResourceNotFoundException("MDTInstance", "id=gone"));
		when(m_manager.getInstance("broken")).thenThrow(new IllegalStateException("db down"));

		m_mvc.perform(get("/list/elements").param("instance", "gone").param("submodel", "Data"))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
		m_mvc.perform(get("/list/elements").param("instance", "broken").param("submodel", "Data"))
			.andExpect(status().isInternalServerError());
	}

	private static JpaInstanceDescriptor instance(String id) {
		JpaInstanceDescriptor inst = mock(JpaInstanceDescriptor.class);
		when(inst.getInstanceId()).thenReturn(id);
		return inst;
	}

	private static JpaMDTSubmodelDescriptor desc(String id, JpaInstanceDescriptor inst) throws Exception {
		JpaMDTSubmodelDescriptor desc = JpaMDTSubmodelDescriptor.from(new DefaultSubmodelDescriptor.Builder()
																			.id(id).idShort(id).build());
		desc.setInstance(inst);
		return desc;
	}
}
