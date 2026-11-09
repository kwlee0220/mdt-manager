package mdt.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.sm.info.MDTAssetType;


/**
 * JSON 응답의 Content-Type이 요청의 Accept와 상관없이 {@code application/json}인지 확인한다(mdt-manager 검토 A4).
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JsonResponseTest {
	private MockMvc m_mvc;

	@BeforeEach
	public void setUp() {
		InstanceDescriptor desc = new InstanceDescriptor();
		desc.setId("t1");
		desc.setStatus(MDTInstanceStatus.RUNNING);
		desc.setAasId("aas");
		desc.setAssetType(MDTAssetType.Machine);

		JpaInstance inst = mock(JpaInstance.class);
		when(inst.getInstanceDescriptor()).thenReturn(desc);
		when(inst.getMDTSubmodelDescriptorAll()).thenReturn(List.of());
		when(inst.getMDTParameterDescriptorAll())
				.thenReturn(List.of(new MDTParameterDescriptor("p1", null, "xs:int", "ref")));
		when(inst.getMDTOperationDescriptorAll()).thenReturn(List.of());

		AbstractJpaInstanceManager manager = mock(AbstractJpaInstanceManager.class);
		when(manager.getInstance("t1")).thenReturn(inst);
		when(manager.getInstanceAll()).thenReturn(List.<MDTInstance>of(inst));

		MDTInstanceManagerController ctrl = new MDTInstanceManagerController();
		ReflectionTestUtils.setField(ctrl, "m_instanceManager", manager);
		m_mvc = MockMvcBuilders.standaloneSetup(ctrl).build();
	}

	@Test
	public void jsonEndpointsDeclareJsonEvenIfClientAsksForAnything() throws Exception {
		for ( String path: List.of("/instances", "/instances/model", "/instances/t1/model",
									"/instances/t1/model/submodels", "/instances/t1/model/parameters",
									"/instances/t1/model/operations") ) {
			m_mvc.perform(get("/instance-manager" + path).accept(MediaType.ALL))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
		}
	}

	@Test
	public void modelsUseTheSameSerializerAsTheirParts() throws Exception {
		// null 필드는 AAS4J 직렬화기가 쓰지 않는다(/model/parameters와 같은 모양).
		m_mvc.perform(get("/instance-manager/instances/t1/model"))
			.andExpect(jsonPath("$.parameters[0].id").value("p1"))
			.andExpect(jsonPath("$.parameters[0].name").doesNotExist());
		m_mvc.perform(get("/instance-manager/instances/model"))
			.andExpect(jsonPath("$[0].id").value("t1"))
			.andExpect(jsonPath("$[0].parameters[0].name").doesNotExist());
		m_mvc.perform(get("/instance-manager/instances"))
			.andExpect(jsonPath("$[0].id").value("t1"));
	}
}
