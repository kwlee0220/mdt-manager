package mdt.instance.jpa;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetKind;
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

import utils.Indexed;
import utils.InternalException;
import utils.KeyValue;
import utils.stream.FStream;

import mdt.model.DescriptorUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.instance.MDTOperationDescriptor.ArgumentDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor.MDTCompositionDependency;
import mdt.model.instance.MDTTwinCompositionDescriptor.MDTCompositionItem;
import mdt.model.sm.SubmodelUtils;
import mdt.model.sm.info.DefaultCompositionDependency;
import mdt.model.sm.info.DefaultCompositionItem;
import mdt.model.sm.info.MDTAssetType;

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

	@Column(name="instance_id", length=64, unique=true) private String id;
	@Column(name="status") @Enumerated(EnumType.STRING) private MDTInstanceStatus status;
	@Column(name="base_endpoint", length=255) private String baseEndpoint;
	@Column(name="arguments", length=255) private String arguments;
	
	@Column(name="aas_id", length=255, nullable=false, unique=true) private String aasId;
	@Column(name="aas_id_short", length=64) private String aasIdShort;
	@Column(name="asset_id", length=255) private String globalAssetId;
	@Column(name="asset_type", length=64) @Enumerated(EnumType.STRING) private MDTAssetType assetType;
	@Column(name="asset_kind", length=32) @Enumerated(EnumType.STRING) private AssetKind assetKind;
	
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
			this.id = instId;
			this.aasId = aasDesc.getId();
			this.aasIdShort = aasDesc.getIdShort();
			this.globalAssetId = aasDesc.getGlobalAssetId();

			Property assetTypeProp = SubmodelUtils.traverse(inforSubmodel, "MDTInfo.AssetType", Property.class);
			this.assetType = MDTAssetType.valueOf(assetTypeProp.getValue());
			
			this.assetKind = aasDesc.getAssetKind();
			setAasDescriptor(aasDesc);
			
			this.submodels.clear();
			for ( SubmodelDescriptor smDesc: aasDesc.getSubmodelDescriptors() ) {
				JpaMDTSubmodelDescriptor jpaSmDescs = JpaMDTSubmodelDescriptor.from(smDesc);
				jpaSmDescs.setInstance(this);
				this.submodels.add(jpaSmDescs);
			}
		}
		catch ( SerializationException e ) {
			throw new InternalException("" + e);
		}
	}
	
	public AssetAdministrationShellDescriptor getAASShellDescriptor() {
		try {
			String aasDescJson = new String(aasDescJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer().read(aasDescJson, AssetAdministrationShellDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize AAS Descriptor: " + e);
		}
	}
	
	public void setAasDescriptor(AssetAdministrationShellDescriptor aasDesc) {
		try {
			this.aasDescJsonBytes = MDTModelSerDe.getJsonSerializer().write(aasDesc).getBytes(StandardCharsets.UTF_8);
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to serialize AAS Descriptor: " + e);
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
			throw new InternalException("Failed to serialize Asset Parameters: " + e);
		}
	
		return instDesc;
	}

	public static JpaInstanceDescriptor from(String instId, AssetAdministrationShellDescriptor aasDesc,
											Submodel inforSubmodel) {
		return new JpaInstanceDescriptor(instId, aasDesc, inforSubmodel);
	}
	
	public void updateFrom(AssetAdministrationShellDescriptor aasDesc) {
		Preconditions.checkArgument(getId().equals(aasDesc.getId()));
		
		this.aasId = aasDesc.getIdShort();
		this.globalAssetId = aasDesc.getGlobalAssetId();
		this.assetType = MDTAssetType.valueOf(aasDesc.getAssetType());
		this.assetKind = aasDesc.getAssetKind();
		
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
			this.submodels = updateds;
		}
		catch ( SerializationException  e ) {
			throw new IllegalArgumentException("Failed to serialize JSON, cause=" + e);
		}
	}
	
	public MDTTwinCompositionDescriptor getTwinComposition() {
		if ( this.twinCompositionJsonBytes == null || this.twinCompositionJsonBytes.length == 0 ) {
			String compType = this.assetType.toString();
			return new MDTTwinCompositionDescriptor(getId(), compType,
															Collections.emptyList(), Collections.emptyList());
		}
		
		try {
			String json = new String(this.twinCompositionJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer()
								.read(json, MDTTwinCompositionDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize MDTTwinCompositionDescriptor: " + e);
		}
	}
	
	public void setTwinComposition(MDTTwinCompositionDescriptor twinComp) {
		Preconditions.checkArgument(twinComp != null, "twinComp must not be null");
		
		try {
			this.twinCompositionJsonBytes = MDTModelSerDe.getJsonSerializer()
														.write(twinComp).getBytes(StandardCharsets.UTF_8);
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to serialize MDTTwinCompositionDescriptor: " + e);
		}
	}
	
	public AssetAdministrationShellDescriptor getAssetAdministrationShellDescriptor() {
		try {
			String aasDescJson = new String(aasDescJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer().read(aasDescJson, AssetAdministrationShellDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize AAS Descriptor: " + e);
		}
	}
	
	@Override
	public String toString() {
		return String.format("InstantDescriptor[%s, %s]", this.id, this.status);
	}

	private void loadJpaParameterList(Submodel submodel) {
		Preconditions.checkArgument(getAssetType() != null,
									"instance assetType is null: instance=%s", getId());
		
		switch ( getAssetType() ) {
			case Machine:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading EQUIPMENT parameters: instance=" + getId());
				}
				loadParameters(getId(), submodel, "Equipment");
				break;
			case Process:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading OPERATION parameters: instance=" + getId());
				}
				loadParameters(getId(), submodel, "Operation");
				break;
			case Line:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading Line parameters: instance=" + getId());
				}
				break;
			case Factory:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading Factory parameters: instance=" + getId());
				}
				break;
			default:
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("loading Unknown Asset parameters: instance=" + getId());
				}
				try {
					loadParameters(getId(), submodel, "Equipment");
				}
				catch ( Exception e ) {
					loadParameters(getId(), submodel, "Operation");
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
									String id = SubmodelUtils.getPropertyById(smc, "ParameterID").value().getValue();
									String name = SubmodelUtils.findPropertyById(smc, "ParameterName")
																.map(Indexed::value)
																.map(prop -> prop.getValue())
																.getOrNull();
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
		catch ( ResourceNotFoundException e ) { }
	}
	private JpaMDTParameterDescriptor toJpaParameterDescriptor(SubmodelElement paramValue, Map<String,String> nameMap) {
		String paramId = SubmodelUtils.traverse(paramValue, "ParameterID", Property.class).getValue();
		String paramName = nameMap.get(id);
		SubmodelElement valueSme = SubmodelUtils.traverse(paramValue, "ParameterValue");
		
		String reference = String.format("param:%s:%s", this.id, paramId);
		
		JpaMDTParameterDescriptor desc = new JpaMDTParameterDescriptor();
		desc.setInstance(this);
		desc.setId(paramId);
		desc.setName(paramName);
		desc.setValueType(SubmodelUtils.getTypeString(valueSme));
		desc.setReference(reference);
		
		return desc;
	}

	private void loadJpaOperationDescriptor(Submodel submodel, String opType) {
		JpaMDTOperationDescriptor opDesc = new JpaMDTOperationDescriptor();
		
		List<SubmodelElement> inputs = SubmodelUtils.traverse(submodel, opType + "Info.Inputs",
																SubmodelElementList.class).getValue();
		List<SubmodelElement> outputs = SubmodelUtils.traverse(submodel, opType + "Info.Outputs",
																SubmodelElementList.class).getValue();
		
		String inPrefix = String.format("oparg:%s:%s:in", getId(), submodel.getIdShort());
		String outPrefix = String.format("oparg:%s:%s:out", getId(), submodel.getIdShort());
		
		opDesc.setInstance(this);
		opDesc.setId(submodel.getIdShort());
		opDesc.setOperationType(opType);
		opDesc.setInputArguments(toArgumentDescriptorList(opDesc, inPrefix, inputs, "Input"));
		opDesc.setOutputArguments(toArgumentDescriptorList(opDesc, outPrefix, outputs, "Output"));
		
		this.operations.add(opDesc);
	}
	private List<ArgumentDescriptor> toArgumentDescriptorList(JpaMDTOperationDescriptor opDesc, String refPrefix,
																List<SubmodelElement> args, String inout) {
		return FStream.from(args)
						.castSafely(SubmodelElementCollection.class)
						.map(arg -> toArgumentDescriptor(opDesc, refPrefix, arg, inout))
						.toList();
	}
	private ArgumentDescriptor toArgumentDescriptor(JpaMDTOperationDescriptor opDesc,
													String refPrefix,
													SubmodelElementCollection arg, String inout) {
		String argId = SubmodelUtils.traverse(arg, inout + "ID", Property.class).getValue();
		SubmodelElement argValue = SubmodelUtils.traverse(arg, inout + "Value");
		String valueType = SubmodelUtils.getTypeString(argValue);
		String reference = String.format("%s:%s", refPrefix, argId);
		
		return new ArgumentDescriptor(argId, valueType, reference);
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
			return new MDTTwinCompositionDescriptor(getId(), compType, items, deps);
		}
		
		String compId = SubmodelUtils.findFieldById(twinComp, "CompositionID", Property.class)
									.map(Indexed::value)
									.map(prop -> prop.getValue())
									.getOrNull();
		if ( compId == null ) {
			String compType = getAssetType().toString();
			return new MDTTwinCompositionDescriptor(getId(), compType, items, deps);
		}
		
		String compType = SubmodelUtils.getFieldById(twinComp, "CompositionType", Property.class)
										.value().getValue();
		if ( compType == null ) {
			compType = getAssetType().toString();
		}
		items = SubmodelUtils.findFieldById(twinComp, "CompositionItems", SubmodelElementList.class)
								.map(Indexed::value)
								.map(list -> FStream.from(list.getValue())
											.castSafely(SubmodelElementCollection.class)
											.map(this::toCompositionItem)
											.toList())
								.getOrElse(Lists.newArrayList());
		deps = SubmodelUtils.findFieldById(twinComp, "CompositionDependencies", SubmodelElementList.class)
							.map(Indexed::value)
							.map(list -> FStream.from(list.getValue())
												.castSafely(SubmodelElementCollection.class)
												.map(this::toCompositionDependency)
												.toList())
							.getOrElse(Lists.newArrayList());
		
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
		desc.setId(getId());
		desc.setStatus(getStatus());
		desc.setBaseEndpoint(getBaseEndpoint());
		desc.setAasId(getAasId());
		desc.setAasIdShort(getAasIdShort());
		desc.setGlobalAssetId(getGlobalAssetId());
		desc.setAssetType(getAssetType());
		desc.setAssetKind(getAssetKind());
		
		return desc;
	}
}