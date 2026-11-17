package mdt.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetKind;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultAssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultEndpoint;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProtocolInformation;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import mdt.instance.AbstractJpaInstanceManager;
import mdt.instance.JpaInstance;
import mdt.model.AASUtils;


/**
 * AAS4J 디스크립터를 돌려주는 엔드포인트의 JSON을 클라이언트(AAS4J 역직렬화)가 원래 객체로 읽는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class AasDescriptorJsonTest {
	private final JsonDeserializer m_deser = new JsonDeserializer();
	private AssetAdministrationShellDescriptor m_desc;
	private MockMvc m_mvc;

	@BeforeEach
	public void setUp() {
		m_desc = descriptor();
		AbstractJpaInstanceManager manager = mock(AbstractJpaInstanceManager.class);
		JpaInstance inst = mock(JpaInstance.class);
		when(manager.getInstance("t1")).thenReturn(inst);
		when(manager.getInstanceAllByAssetId("https://example.com/asset/t1")).thenReturn(List.of(inst));
		when(inst.getAASShellDescriptor()).thenReturn(m_desc);
		when(inst.getAASSubmodelDescriptorAll()).thenReturn(m_desc.getSubmodelDescriptors());

		MDTInstanceManagerController instCtrl = new MDTInstanceManagerController();
		ReflectionTestUtils.setField(instCtrl, "m_instanceManager", manager);
		JpaShellRegistryController regCtrl = new JpaShellRegistryController();
		ReflectionTestUtils.setField(regCtrl, "m_instanceManager", manager);
		m_mvc = MockMvcBuilders.standaloneSetup(instCtrl, regCtrl).build();
	}

	/** Spring 기본 매퍼의 JSON은 AAS4J로 되읽으면 원래 객체가 아니다(그래서 AAS JSON 직렬화기를 쓴다). */
	@Test
	public void springDefaultMapperIsNotAasJson() throws Exception {
		String json = Jackson2ObjectMapperBuilder.json().build().writeValueAsString(m_desc);
		org.junit.jupiter.api.Assertions.assertNotEquals(m_desc, m_deser.read(json, AssetAdministrationShellDescriptor.class));
	}

	@Test
	public void shellDescriptorRoundTrips() throws Exception {
		String json = body("/instance-manager/instances/t1/aas/shell_descriptor");
		assertEquals(m_desc, m_deser.read(json, AssetAdministrationShellDescriptor.class));
	}

	@Test
	public void submodelDescriptorsRoundTrip() throws Exception {
		String json = body("/instance-manager/instances/t1/aas/submodel_descriptors");
		assertEquals(m_desc.getSubmodelDescriptors(), m_deser.readList(json, SubmodelDescriptor.class));
	}

	@Test
	public void registryByAssetIdRoundTrips() throws Exception {
		String encoded = AASUtils.encodeBase64UrlSafe("https://example.com/asset/t1");
		String json = body("/aas_registry/shell-descriptors/asset/" + encoded);
		assertEquals(List.of(m_desc), m_deser.readList(json, AssetAdministrationShellDescriptor.class));
	}

	private String body(String path) throws Exception {
		return m_mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static AssetAdministrationShellDescriptor descriptor() {
		return new DefaultAssetAdministrationShellDescriptor.Builder()
				.id("https://example.com/aas/t1")
				.idShort("t1")
				.assetKind(AssetKind.INSTANCE)
				.globalAssetId("https://example.com/asset/t1")
				.endpoints(List.of(new DefaultEndpoint.Builder()
						._interface("AAS-3.0")
						.protocolInformation(new DefaultProtocolInformation.Builder()
								.href("https://h:1/api/v3.0/shells/x").build())
						.build()))
				.submodelDescriptors(List.of(new DefaultSubmodelDescriptor.Builder()
						.id("https://example.com/sm/Data")
						.idShort("Data")
						.semanticId(new DefaultReference.Builder()
								.type(ReferenceTypes.EXTERNAL_REFERENCE)
								.keys(List.of(new DefaultKey.Builder().type(KeyTypes.GLOBAL_REFERENCE)
																	.value("https://etri.re.kr/mdt/Submodel/Data/1/1").build()))
								.build())
						.build()))
				.build();
	}
}
