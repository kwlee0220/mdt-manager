package mdt.instance.jpa;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.Property;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import utils.InternalException;
import utils.KeyValue;
import utils.stream.FStream;

import mdt.model.DescriptorUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.ModelValidationException;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.instance.MDTOperationDescriptor.ArgumentDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor.MDTCompositionDependency;
import mdt.model.instance.MDTTwinCompositionDescriptor.MDTCompositionItem;
import mdt.model.sm.SubmodelElementTypeDescriptor;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.info.DefaultCompositionDependency;
import mdt.model.sm.info.DefaultCompositionItem;
import mdt.model.sm.info.MDTAssetType;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@NoArgsConstructor
@Entity
@Table(
	name="instance_descriptors",
	indexes = {
		@Index(name="instance_id_idx", columnList="instance_id", unique=true),
		@Index(name="aas_id_idx", columnList="aas_id", unique=true),
		@Index(name="aas_idshort_idx", columnList="aas_id_short")
	})
@Getter @Setter
public class JpaInstanceDescriptor {
	private static final Logger s_logger = LoggerFactory.getLogger(JpaInstanceDescriptor.class);
	
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id") private Long rowId;

	@Column(name="instance_id", length=64, unique=true) private String instanceId;
	@Column(name="status") @Enumerated(EnumType.STRING) private MDTInstanceStatus status;
	@Column(name="base_endpoint", length=255) private String baseEndpoint;
	@Column(name="arguments", length=255) private String arguments;
	
	@Column(name="aas_id", length=255, nullable=false, unique=true) private String aasId;
	@Column(name="aas_id_short", length=64) private String aasIdShort;
	@Column(name="asset_id", length=255) private String globalAssetId;
	@Column(name="asset_type", length=64) @Enumerated(EnumType.STRING) private MDTAssetType assetType;
	
	@Column(columnDefinition = "bytea", nullable = false)
	private byte[] aasDescJsonBytes;

	@OneToMany(fetch=FetchType.LAZY, cascade=CascadeType.ALL, mappedBy="instance", orphanRemoval=true)
	private List<JpaMDTSubmodelDescriptor> submodels = Lists.newArrayList();

	@OneToMany(fetch=FetchType.LAZY, cascade=CascadeType.ALL, mappedBy="instance", orphanRemoval=true)
	private List<JpaMDTParameterDescriptor> parameters = Lists.newArrayList();

	@OneToMany(fetch=FetchType.LAZY, cascade=CascadeType.ALL, mappedBy="instance", orphanRemoval=true)
	private List<JpaMDTOperationDescriptor> operations = Lists.newArrayList();

	@Column(columnDefinition = "bytea")
	private byte[] twinCompositionJsonBytes;
	
	private JpaInstanceDescriptor(String instId, AssetAdministrationShellDescriptor aasDesc,
									Submodel inforSubmodel) {
		try {
			this.instanceId = instId;
			this.aasId = aasDesc.getId();
			this.aasIdShort = aasDesc.getIdShort();
			this.globalAssetId = aasDesc.getGlobalAssetId();

			Property assetTypeProp = SubmodelUtils.traverse(inforSubmodel, "MDTInfo.AssetType", Property.class);
			this.assetType = MDTAssetType.valueOf(assetTypeProp.getValue());
			
			setAasDescriptor(aasDesc);
			
			this.submodels.clear();
			for ( SubmodelDescriptor smDesc: aasDesc.getSubmodelDescriptors() ) {
				JpaMDTSubmodelDescriptor jpaSmDescs = JpaMDTSubmodelDescriptor.from(smDesc);
				jpaSmDescs.setInstance(this);
				this.submodels.add(jpaSmDescs);
			}
		}
		catch ( SerializationException e ) {
			throw new InternalException(e);
		}
	}
	
	public void setAasDescriptor(AssetAdministrationShellDescriptor aasDesc) {
		try {
			this.aasDescJsonBytes = MDTModelSerDe.getJsonSerializer().write(aasDesc).getBytes(StandardCharsets.UTF_8);
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to serialize AAS Descriptor: " + e, e);
		}
	}

	public static JpaInstanceDescriptor build(String instId, AssetAdministrationShell shell,
												List<Submodel> submodels) {
		// AAS 관련 Descriptor를 생성한다.
		AssetAdministrationShellDescriptor aasDesc
							= DescriptorUtils.createAssetAdministrationShellDescriptor(shell, null);
		List<SubmodelDescriptor> smDescList
					= FStream.from(submodels)
							.map(sm -> DescriptorUtils.createSubmodelDescriptor(sm, null))
							.cast(SubmodelDescriptor.class)
							.toList();
		aasDesc.setSubmodelDescriptors(smDescList);
		
		Submodel inforSubmodel = FStream.from(submodels)
										.findFirst(sm -> SubmodelUtils.isInformationModel(sm))
										.getOrThrow(() -> new IllegalArgumentException("No InformationModel Submodel found in the instance: id=" + instId));
		
		JpaInstanceDescriptor instDesc = from(instId, aasDesc, inforSubmodel);
		for ( Submodel submodel: submodels ) {
			if ( SubmodelUtils.isDataSubmodel(submodel) ) {
				instDesc.loadJpaParameterList(submodel);
			}
			else if ( SubmodelUtils.isSimulationSubmodel(submodel) ) {
				instDesc.loadJpaOperationDescriptor(submodel, "Simulation");
			}
			else if ( SubmodelUtils.isAISubmodel(submodel) ) {
				instDesc.loadJpaOperationDescriptor(submodel, "AI");
			}
		}
		
		MDTTwinCompositionDescriptor twinComp = instDesc.loadTwinComposition(inforSubmodel);
		try {
			instDesc.twinCompositionJsonBytes = MDTModelSerDe.getJsonSerializer()
															.write(twinComp).getBytes(StandardCharsets.UTF_8);
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to serialize Asset Parameters: " + e, e);
		}
	
		return instDesc;
	}

	public static JpaInstanceDescriptor from(String instId, AssetAdministrationShellDescriptor aasDesc,
											Submodel inforSubmodel) {
		return new JpaInstanceDescriptor(instId, aasDesc, inforSubmodel);
	}
	
	/**
	 * AAS 등록정보로 이 디스크립터의 AAS 정보(idShort, globalAssetId, assetType, AAS 디스크립터 JSON)와 submodel
	 * 목록을 갱신한다. parameters·operations·twin composition은 바꾸지 않는다.
	 * <p>
	 * {@code assetType}이 없거나 {@link MDTAssetType} 값이 아니면 기존 값을 유지한다. instance id와
	 * AAS id는 서로 다른 식별자이므로, 비교하는 것은 AAS id다.
	 *
	 * @param aasDesc	새 AAS 등록정보. id가 이 디스크립터의 AAS id와 같아야 한다.
	 * @throws IllegalArgumentException	AAS id가 다른 경우.
	 */
	public void updateFrom(AssetAdministrationShellDescriptor aasDesc) {
		Preconditions.checkArgument(this.aasId.equals(aasDesc.getId()),
									"AAS id mismatch: expected=%s, given=%s", this.aasId, aasDesc.getId());
		
		this.aasIdShort = aasDesc.getIdShort();
		this.globalAssetId = aasDesc.getGlobalAssetId();
		this.assetType = parseAssetType(aasDesc.getAssetType(), this.assetType);
		
		try {
			setAasDescriptor(aasDesc);
			
			List<JpaMDTSubmodelDescriptor> updateds = Lists.newArrayList();
			Map<String,JpaMDTSubmodelDescriptor> prevMap = FStream.from(this.submodels)
																		.tagKey(JpaMDTSubmodelDescriptor::getIdShort)
																		.toMap();
			for ( SubmodelDescriptor smDesc: aasDesc.getSubmodelDescriptors() ) {
				JpaMDTSubmodelDescriptor jid = prevMap.remove(smDesc.getIdShort());
				if ( jid != null ) {
					jid.updateFrom(smDesc);
				}
				else {
					jid = JpaMDTSubmodelDescriptor.from(smDesc);
				}
				updateds.add(jid);
			}
			FStream.from(updateds).forEach(jisd -> jisd.setInstance(this));
			// orphanRemoval 컬렉션은 Hibernate가 관리하는 컬렉션 객체를 그대로 두고 내용만 바꿔야 한다. 빠진 항목은
			// orphan으로 삭제된다. 새 리스트를 대입하면 Hibernate가 "A collection with
			// cascade=all-delete-orphan was no longer referenced"로 실패한다.
			this.submodels.clear();
			this.submodels.addAll(updateds);
		}
		catch ( SerializationException  e ) {
			throw new IllegalArgumentException("Failed to serialize JSON, cause=" + e, e);
		}
	}
	
	private MDTAssetType parseAssetType(String assetType, MDTAssetType current) {
		if ( assetType == null ) {
			return current;
		}
		try {
			return MDTAssetType.valueOf(assetType);
		}
		catch ( IllegalArgumentException e ) {
			s_logger.warn("ignore unknown assetType: instance={}, assetType={}, keep={}", getInstanceId(), assetType, current);
			return current;
		}
	}
	
	public MDTTwinCompositionDescriptor getTwinComposition() {
		if ( this.twinCompositionJsonBytes == null || this.twinCompositionJsonBytes.length == 0 ) {
			String compType = this.assetType.toString();
			return new MDTTwinCompositionDescriptor(getInstanceId(), compType,
															Collections.emptyList(), Collections.emptyList());
		}
		
		try {
			String json = new String(this.twinCompositionJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer()
								.read(json, MDTTwinCompositionDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize MDTTwinCompositionDescriptor: " + e, e);
		}
	}
	
	public void setTwinComposition(MDTTwinCompositionDescriptor twinComp) {
		Preconditions.checkArgument(twinComp != null, "twinComp must not be null");
		
		try {
			this.twinCompositionJsonBytes = MDTModelSerDe.getJsonSerializer()
														.write(twinComp).getBytes(StandardCharsets.UTF_8);
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to serialize MDTTwinCompositionDescriptor: " + e, e);
		}
	}
	
	public AssetAdministrationShellDescriptor getAssetAdministrationShellDescriptor() {
		try {
			String aasDescJson = new String(aasDescJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer().read(aasDescJson, AssetAdministrationShellDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize AAS Descriptor: " + e, e);
		}
	}
	
	@Override
	public String toString() {
		return String.format("InstantDescriptor[%s, %s]", this.instanceId, this.status);
	}

	private void loadJpaParameterList(Submodel submodel) {
		Preconditions.checkArgument(getAssetType() != null,
									"instance assetType is null: instance=%s", getInstanceId());
		
		switch ( getAssetType() ) {
			case Machine:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading EQUIPMENT parameters: instance=" + getInstanceId());
				}
				loadParameters(getInstanceId(), submodel, "Equipment");
				break;
			case Process:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading OPERATION parameters: instance=" + getInstanceId());
				}
				loadParameters(getInstanceId(), submodel, "Operation");
				break;
			case Line:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading Line parameters: instance=" + getInstanceId());
				}
				break;
			case Factory:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading Factory parameters: instance=" + getInstanceId());
				}
				break;
		}
	}
	
	private void loadParameters(String instId, Submodel submodel, String entityName) {
		this.parameters.clear();
		
		try {
			String infoIdPath = String.format("DataInfo.%s.%sParameters", entityName, entityName);
			List<SubmodelElement> paramInfos = SubmodelUtils.traverse(submodel, infoIdPath,
																	SubmodelElementList.class).getValue();
			Map<String,String> nameMap
						= FStream.from(paramInfos)
								.mapToKeyValue(info -> {
									SubmodelElementCollection smc = (SubmodelElementCollection)info;
									String id = SubmodelUtils.getFieldById(smc, "ParameterID", Property.class).getValue();
									Property prop = SubmodelUtils.findFieldById(smc, "ParameterName", Property.class);
									String name = (prop != null) ? prop.getValue() : null;
									return KeyValue.of(id, name);
								})
								.toMap();

			String valueIdPath = String.format("DataInfo.%s.%sParameterValues", entityName, entityName);
			List<SubmodelElement> paramValues = SubmodelUtils.traverse(submodel, valueIdPath,
																	SubmodelElementList.class).getValue();
			FStream.from(paramValues)
					.map(param -> toJpaParameterDescriptor(param, nameMap))
					.forEach(desc -> this.parameters.add(desc));
		}
		catch ( ResourceNotFoundException e ) {
			// 구조가 잘못된 모델을 파라미터 0개로 등록하지 않고 거부한다.
			throw new ModelValidationException(String.format("invalid Data submodel: instance=%s, entity=%s, cause=%s",
															instId, entityName, e.getMessage()));
		}
	}
	private JpaMDTParameterDescriptor toJpaParameterDescriptor(SubmodelElement paramValue, Map<String,String> nameMap) {
		String paramId = SubmodelUtils.traverse(paramValue, "ParameterID", Property.class).getValue();
		if ( !nameMap.containsKey(paramId) ) {
			throw new IllegalArgumentException("ParameterID in ParameterValues[] is not found in Parameter[]: "
												+ "ParameterID=" + paramId);
		}
		String paramName = nameMap.get(paramId);
		SubmodelElement valueSme = SubmodelUtils.traverse(paramValue, "ParameterValue");
		
		String reference = String.format("param:%s:%s", this.instanceId, paramId);
		
		JpaMDTParameterDescriptor desc = new JpaMDTParameterDescriptor();
		desc.setInstance(this);
		desc.setId(paramId);
		desc.setName(paramName);
		desc.setValueType(SubmodelElementTypeDescriptor.getValueTypeString(valueSme));
		desc.setReference(reference);
		
		return desc;
	}

	private void loadJpaOperationDescriptor(Submodel submodel, String opType) {
		JpaMDTOperationDescriptor opDesc = new JpaMDTOperationDescriptor();
		
		List<SubmodelElement> inputs = SubmodelUtils.traverse(submodel, opType + "Info.Inputs",
																SubmodelElementList.class).getValue();
		List<SubmodelElement> outputs = SubmodelUtils.traverse(submodel, opType + "Info.Outputs",
																SubmodelElementList.class).getValue();
		
		String inRefPrefix = String.format("oparg:%s:%s:in", getInstanceId(), submodel.getIdShort());
		String outRefPrefix = String.format("oparg:%s:%s:out", getInstanceId(), submodel.getIdShort());
		String endpointPrefix = String.format("%sInfo", opType);
		
		opDesc.setInstance(this);
		opDesc.setId(submodel.getIdShort());
		opDesc.setOperationType(opType);
		opDesc.setInputArguments(toArgumentDescriptorList(opDesc, inputs, inRefPrefix, endpointPrefix, "Input"));
		opDesc.setOutputArguments(toArgumentDescriptorList(opDesc, outputs, outRefPrefix, endpointPrefix, "Output"));
		
		this.operations.add(opDesc);
	}
	private List<ArgumentDescriptor> toArgumentDescriptorList(JpaMDTOperationDescriptor opDesc,
																List<SubmodelElement> args,
																String refPrefix, String endpointPrefix,
																String argKind) {
		return FStream.from(args)
						.castSafely(SubmodelElementCollection.class)
						.zipWithIndex()
						.map(idxed -> toArgumentDescriptor(opDesc, idxed.value(), idxed.index(),
															refPrefix, endpointPrefix, argKind))
						.toList();
	}
	private ArgumentDescriptor toArgumentDescriptor(JpaMDTOperationDescriptor opDesc,
													SubmodelElementCollection arg, int argIndex,
													String refPrefix, String endpointPrefix, String argKind) {
		String argId = SubmodelUtils.traverse(arg, argKind + "ID", Property.class).getValue();
		SubmodelElement argValue = SubmodelUtils.traverse(arg, argKind + "Value");
		String valueType = SubmodelElementTypeDescriptor.getValueTypeString(argValue); 
		String reference = String.format("%s:%s", refPrefix, argId);
		String endpoint = String.format("%s.%ss[%d].%sValue", endpointPrefix, argKind, argIndex, argKind);
		
		return new ArgumentDescriptor(argId, endpoint, valueType, reference);
	}

	private MDTTwinCompositionDescriptor loadTwinComposition(Submodel inforSubmodel) {
		List<MDTCompositionItem> items = Collections.emptyList();
		List<MDTCompositionDependency> deps = Collections.emptyList();
		
		SubmodelElement twinComp = null;
		try {
			twinComp = SubmodelUtils.traverse(inforSubmodel, "TwinComposition", SubmodelElementCollection.class);
		}
		catch ( ResourceNotFoundException e ) {
			String compType = getAssetType().toString();
			return new MDTTwinCompositionDescriptor(getInstanceId(), compType, items, deps);
		}
		
		Property prop = SubmodelUtils.findFieldById(twinComp, "CompositionID", Property.class);
		String compId = (prop != null) ? prop.getValue() : null;
		if ( compId == null ) {
			String compType = getAssetType().toString();
			return new MDTTwinCompositionDescriptor(getInstanceId(), compType, items, deps);
		}
		
		String compType = SubmodelUtils.getFieldById(twinComp, "CompositionType", Property.class).getValue();
		if ( compType == null ) {
			compType = getAssetType().toString();
		}
		SubmodelElementList list = SubmodelUtils.findFieldById(twinComp, "CompositionItems", SubmodelElementList.class);
		items = Lists.newArrayList();
		if ( list != null ) {
			items = FStream.from(list.getValue())
							.castSafely(SubmodelElementCollection.class)
							.map(this::toCompositionItem)
							.toList();
		}
		
		deps = Lists.newArrayList();
		list = SubmodelUtils.findFieldById(twinComp, "CompositionDependencies", SubmodelElementList.class);
		if ( list != null ) {
			deps = FStream.from(list.getValue())
                            .castSafely(SubmodelElementCollection.class)
                            .map(this::toCompositionDependency)
                            .toList();
		}
		
		return new MDTTwinCompositionDescriptor(compId, compType, items, deps);
		
	}
	private MDTCompositionItem toCompositionItem(SubmodelElementCollection smc) {
		DefaultCompositionItem item = new DefaultCompositionItem();
		item.updateFromAasModel(smc);
		
		return new MDTCompositionItem(item.getID(), item.getReference());
	}
	private MDTCompositionDependency toCompositionDependency(SubmodelElementCollection smc) {
		DefaultCompositionDependency dep = new DefaultCompositionDependency();
		dep.updateFromAasModel(smc);
		
		return new MDTCompositionDependency(dep.getDependencyType(), dep.getSourceId(), dep.getTargetId());
	}
	
	public InstanceDescriptor toInstanceDescriptor() {
		InstanceDescriptor desc = new InstanceDescriptor();
		desc.setId(getInstanceId());
		desc.setStatus(getStatus());
		desc.setBaseEndpoint(getBaseEndpoint());
		desc.setAasId(getAasId());
		desc.setAasIdShort(getAasIdShort());
		desc.setGlobalAssetId(getGlobalAssetId());
		desc.setAssetType(getAssetType());
		
		return desc;
	}
}