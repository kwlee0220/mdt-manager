package mdt.instance.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultAssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;


/**
 * {@link JpaInstanceDescriptor#updateFrom(AssetAdministrationShellDescriptor)}가 submodel 목록을 갱신하고, 빠진
 * submodel 행을 orphan으로 지우는지 H2로 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@DataJpaTest
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:d9;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
	"spring.jpa.hibernate.ddl-auto=create-drop",
})
public class JpaInstanceDescriptorUpdateTest {
	private static final String AAS_ID = "https://example.com/aas/t1";
	
	@Autowired TestEntityManager m_em;

	@Test
	public void updateRejectsOtherAasIdAndKeepsUnknownAssetType() {
		JpaInstanceDescriptor desc = JpaInstanceDescriptor.from("t1", aas(AAS_ID, "Data"), infoSubmodel());
		assertThrows(IllegalArgumentException.class, () -> desc.updateFrom(aas("https://example.com/aas/other", "Data")));

		AssetAdministrationShellDescriptor unknownType = aas(AAS_ID, "Data");
		unknownType.setAssetType("NotAnAssetType");
		unknownType.setIdShort("renamed");
		desc.updateFrom(unknownType);
		assertEquals(mdt.model.sm.info.MDTAssetType.Machine, desc.getAssetType());
		assertEquals("renamed", desc.getAasIdShort());
	}

	@Test
	public void updateReplacesSubmodelsAndRemovesOrphans() {
		// instance id와 AAS id는 다른 식별자다.
		JpaInstanceDescriptor desc = JpaInstanceDescriptor.from("t1", aas(AAS_ID, "Data", "Simulation"), infoSubmodel());
		m_em.persistAndFlush(desc);
		m_em.clear();

		JpaInstanceDescriptor loaded = m_em.find(JpaInstanceDescriptor.class, desc.getRowId());
		loaded.updateFrom(aas(AAS_ID, "Data", "Timeseries"));
		m_em.flush();		// all-delete-orphan 컬렉션을 바꿔치기하면 여기서 HibernateException
		m_em.clear();

		JpaInstanceDescriptor reloaded = m_em.find(JpaInstanceDescriptor.class, desc.getRowId());
		assertEquals(List.of("Data", "Timeseries"),
					reloaded.getSubmodels().stream().map(JpaMDTSubmodelDescriptor::getIdShort).sorted().toList());
		long rows = m_em.getEntityManager()
						.createQuery("select count(s) from JpaMDTSubmodelDescriptor s", Long.class)
						.getSingleResult();
		assertEquals(2L, rows, "the removed submodel row must be deleted as an orphan");
	}

	private static AssetAdministrationShellDescriptor aas(String id, String... smIdShorts) {
		List<SubmodelDescriptor> sms = java.util.Arrays.stream(smIdShorts)
				.map(idShort -> (SubmodelDescriptor)new DefaultSubmodelDescriptor.Builder()
														.id(id + "/sm/" + idShort)
														.idShort(idShort)
														.build())
				.toList();
		return new DefaultAssetAdministrationShellDescriptor.Builder()
								.id(id)
								.idShort("t1")
								.globalAssetId(id + "/asset")
								.assetType("Machine")
								.submodelDescriptors(sms)
								.build();
	}

	private static Submodel infoSubmodel() {
		return new DefaultSubmodel.Builder()
						.id("t1/sm/InformationModel")
						.idShort("InformationModel")
						.submodelElements(new DefaultSubmodelElementCollection.Builder()
								.idShort("MDTInfo")
								.value(new DefaultProperty.Builder().idShort("AssetType").value("Machine").build())
								.build())
						.build();
	}
}
