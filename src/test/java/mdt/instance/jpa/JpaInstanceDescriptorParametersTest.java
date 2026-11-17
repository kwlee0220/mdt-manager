package mdt.instance.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetKind;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultAssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultAssetInformation;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultKey;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProperty;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultReference;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodel;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelElementList;
import org.junit.jupiter.api.Test;

import mdt.model.ModelValidationException;
import mdt.model.sm.data.Data;
import mdt.model.sm.info.InformationModel;


/**
 * Data 서브모델 구조가 잘못된 모델을 파라미터 0개로 등록하지 않고 거부하는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaInstanceDescriptorParametersTest {
	@Test
	public void validStructureIsAccepted() {
		JpaInstanceDescriptor desc = build("Machine", entity("Equipment", true, true));
		assertTrue(desc.getParameters().isEmpty());
		
		desc = build("Process", entity("Operation", true, true));
		assertTrue(desc.getParameters().isEmpty());
	}

	@Test
	public void materialReadsEquipment() {
		// Material은 Machine과 같이 DataInfo.Equipment를 읽는다.
		assertTrue(build("Material", entity("Equipment", true, true)).getParameters().isEmpty());
		assertThrows(ModelValidationException.class, () -> build("Material", entity("Operation", true, true)));
	}

	@Test
	public void missingEntityIsRejected() {
		// Machine은 DataInfo.Equipment를 읽는다.
		assertThrows(ModelValidationException.class, () -> build("Machine", entity("Operation", true, true)));
		assertThrows(ModelValidationException.class, () -> build("Process", entity("Equipment", true, true)));
	}

	@Test
	public void missingListsAreRejected() {
		assertThrows(ModelValidationException.class, () -> build("Machine", entity("Equipment", false, true)));
		assertThrows(ModelValidationException.class, () -> build("Machine", entity("Equipment", true, false)));
	}

	@Test
	public void lineAndFactoryDoNotLoadParameters() {
		assertEquals(0, build("Line", entity("Other", false, false)).getParameters().size());
		assertEquals(0, build("Factory", entity("Other", false, false)).getParameters().size());
	}

	private static JpaInstanceDescriptor build(String assetType, SubmodelElement dataEntity) {
		AssetAdministrationShell aas = new DefaultAssetAdministrationShell.Builder()
														.id("https://example.com/aas/t1")
														.idShort("t1")
														.assetInformation(new DefaultAssetInformation.Builder()
																			.assetKind(AssetKind.INSTANCE)
																			.globalAssetId("t1-asset")
																			.build())
														.build();
		Submodel info = new DefaultSubmodel.Builder()
							.id("t1/sm/InformationModel")
							.idShort("InformationModel")
							.semanticId(semanticId(InformationModel.SEMANTIC_ID))
							.submodelElements(new DefaultSubmodelElementCollection.Builder()
									.idShort("MDTInfo")
									.value(new DefaultProperty.Builder().idShort("AssetType").value(assetType).build())
									.build())
							.build();
		Submodel data = new DefaultSubmodel.Builder()
							.id("t1/sm/Data")
							.idShort("Data")
							.semanticId(semanticId(Data.SEMANTIC_ID))
							.submodelElements(new DefaultSubmodelElementCollection.Builder()
									.idShort("DataInfo")
									.value(dataEntity)
									.build())
							.build();
		return JpaInstanceDescriptor.build("t1", aas, List.of(info, data));
	}

	private static SubmodelElement entity(String name, boolean hasParameters, boolean hasValues) {
		DefaultSubmodelElementCollection.Builder builder = new DefaultSubmodelElementCollection.Builder().idShort(name);
		if ( hasParameters ) {
			builder.value(new DefaultSubmodelElementList.Builder().idShort(name + "Parameters").build());
		}
		if ( hasValues ) {
			builder.value(new DefaultSubmodelElementList.Builder().idShort(name + "ParameterValues").build());
		}
		return builder.build();
	}

	private static Reference semanticId(String id) {
		return new DefaultReference.Builder()
					.type(ReferenceTypes.EXTERNAL_REFERENCE)
					.keys(new DefaultKey.Builder().type(KeyTypes.GLOBAL_REFERENCE).value(id).build())
					.build();
	}
}
