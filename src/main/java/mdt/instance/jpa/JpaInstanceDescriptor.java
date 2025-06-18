package mdt.instance.jpa;

import java.util.List;
import java.util.Map;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetKind;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import utils.InternalException;
import utils.stream.FStream;

import mdt.model.DescriptorUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.InstanceSubmodelDescriptor;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.instance.MDTOperationDescriptor;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.sm.SubmodelUtils;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@NoArgsConstructor
@Entity
@Table(
	name="instance_descriptors",
	indexes = {
		@Index(name="instance_id_idx", columnList="instance_id", unique=true),
		@Index(name="aas_id_idx", columnList="aas_id", unique=true),
		@Index(name="aas_idshort_idx", columnList="aas_id_short")
	})
public class JpaInstanceDescriptor implements InstanceDescriptor {
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

	@OneToOne(fetch=FetchType.LAZY, cascade=CascadeType.ALL, orphanRemoval=true)
	@JoinColumn(name="aas_descriptor_id")
	private JpaAASDescriptor aasDescriptor;

	@OneToMany(fetch=FetchType.EAGER, cascade=CascadeType.ALL, mappedBy="instance", orphanRemoval=true)
	private List<JpaInstanceSubmodelDescriptor> submodels = Lists.newArrayList();

	@OneToMany(fetch=FetchType.EAGER, cascade=CascadeType.ALL, mappedBy="instance", orphanRemoval=true)
	private List<JpaAssetParameterDescriptor>parameters = Lists.newArrayList();

	@OneToMany(fetch=FetchType.EAGER, cascade=CascadeType.ALL, mappedBy="instance", orphanRemoval=true)
	private List<JpaMDTOperationDescriptor> operations = Lists.newArrayList();
	
	private JpaInstanceDescriptor(String instId, AssetAdministrationShellDescriptor aasDesc) {
		try {
			this.id = instId;
			this.aasId = aasDesc.getId();
			this.aasIdShort = aasDesc.getIdShort();
			this.globalAssetId = aasDesc.getGlobalAssetId();
			this.assetType = MDTAssetType.valueOf(aasDesc.getAssetType());
			this.assetKind = aasDesc.getAssetKind();
			this.aasDescriptor = new JpaAASDescriptor(MDTModelSerDe.getJsonSerializer().write(aasDesc));
			
			this.submodels.clear();
			for ( SubmodelDescriptor smDesc: aasDesc.getSubmodelDescriptors() ) {
				JpaInstanceSubmodelDescriptor jismDesc = JpaInstanceSubmodelDescriptor.from(smDesc);
				jismDesc.setInstance(this);
				this.submodels.add(jismDesc);
			}
		}
		catch ( SerializationException e ) {
			throw new InternalException("" + e);
		}
	}
	
	public void addAssetOperationDescrriptor(JpaMDTOperationDescriptor opDesc) {
		opDesc.setInstance(this);
		this.operations.add(opDesc);
	}

	public static JpaInstanceDescriptor from(String instId, AssetAdministrationShell shell,
												List<Submodel> submodels) {
		AssetAdministrationShellDescriptor aasDesc
							= DescriptorUtils.createAssetAdministrationShellDescriptor(shell, null);
		List<SubmodelDescriptor> smDescList
					= FStream.from(submodels)
							.map(sm -> DescriptorUtils.createSubmodelDescriptor(sm, null))
							.cast(SubmodelDescriptor.class)
							.toList();
		aasDesc.setSubmodelDescriptors(smDescList);
		
		JpaInstanceDescriptor instDesc = from(instId, aasDesc);
		for ( Submodel submodel: submodels ) {
			if ( SubmodelUtils.isDataSubmodel(submodel) ) {
				instDesc.parameters = JpaAssetParameterDescriptor.loadJpaAssetParameterList(instDesc, submodel);
			}
			else if ( SubmodelUtils.isSimulationSubmodel(submodel) ) {
				JpaMDTOperationDescriptor opDesc = JpaMDTOperationDescriptor.loadSimulationDescriptor(instDesc,
																											submodel);
				instDesc.operations.add(opDesc);
			}
			else if ( SubmodelUtils.isAISubmodel(submodel) ) {
				JpaMDTOperationDescriptor opDesc = JpaMDTOperationDescriptor.loadAIDescriptor(instDesc,
																									submodel);
				instDesc.operations.add(opDesc);
			}
		}
	
		return instDesc;
	}

	public static JpaInstanceDescriptor from(String instId, AssetAdministrationShellDescriptor aasDesc) {
		return new JpaInstanceDescriptor(instId, aasDesc);
	}
	
	public void updateFrom(AssetAdministrationShellDescriptor aasDesc) {
		Preconditions.checkArgument(getId().equals(aasDesc.getId()));
		
		this.aasId = aasDesc.getIdShort();
		this.globalAssetId = aasDesc.getGlobalAssetId();
		this.assetType = MDTAssetType.valueOf(aasDesc.getAssetType());
		this.assetKind = aasDesc.getAssetKind();
		
		try {
			String aasJson = MDTModelSerDe.getJsonSerializer().write(aasDesc);
			getAasDescriptor().setJson(aasJson);
			
			List<JpaInstanceSubmodelDescriptor> updateds = Lists.newArrayList();
			Map<String,JpaInstanceSubmodelDescriptor> prevMap = FStream.from(this.submodels)
																		.tagKey(InstanceSubmodelDescriptor::getIdShort)
																		.toMap();
			for ( SubmodelDescriptor smDesc: aasDesc.getSubmodelDescriptors() ) {
				JpaInstanceSubmodelDescriptor jid = prevMap.remove(smDesc.getIdShort());
				if ( jid != null ) {
					jid.updateFrom(smDesc);
				}
				else {
					jid = JpaInstanceSubmodelDescriptor.from(smDesc);
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

	@Override
	@JsonIgnore
	public List<InstanceSubmodelDescriptor> getInstanceSubmodelDescriptorAll() {
		return FStream.from(this.submodels).cast(InstanceSubmodelDescriptor.class).toList();
	}

	@Override
	@JsonIgnore
	public List<MDTParameterDescriptor> getMDTParameterDescriptorAll() {
		return FStream.from(this.parameters).cast(MDTParameterDescriptor.class).toList();
	}
	
	@Override
	@JsonIgnore
	public List<MDTOperationDescriptor> getMDTOperationDescriptorAll() {
		return FStream.from(this.operations).cast(MDTOperationDescriptor.class).toList();
	}
	
	public AssetAdministrationShellDescriptor toAssetAdministrationShellDescriptor() {
		try {
			String json = getAasDescriptor().getJson();
			AssetAdministrationShellDescriptor aasDesc
					= MDTModelSerDe.getJsonDeserializer().read(json, AssetAdministrationShellDescriptor.class);
			if ( getBaseEndpoint() != null ) {
				String aasEp = DescriptorUtils.toAASServiceEndpointString(getBaseEndpoint(), getAasId());
				aasDesc.setEndpoints(DescriptorUtils.newEndpoints(aasEp, "AAS-3.0"));
				
				for ( SubmodelDescriptor smDesc: aasDesc.getSubmodelDescriptors() ) {
					String smEp = DescriptorUtils.toSubmodelServiceEndpointString(getBaseEndpoint(), smDesc.getId());
					smDesc.setEndpoints(DescriptorUtils.newEndpoints(smEp, "SUBMODEL-3.0"));
				}
			}
			
			return aasDesc;
		}
		catch ( DeserializationException e ) {
			throw new InternalException("" + e);
		}
	}
	
	public SubmodelDescriptor toSubmodelDescriptor(JpaInstanceSubmodelDescriptor ismDesc) {
		try {
			SubmodelDescriptor smDesc = MDTModelSerDe.getJsonDeserializer()
													.read(ismDesc.getJson(), SubmodelDescriptor.class);
			if ( getBaseEndpoint() != null ) {
				String smEp = DescriptorUtils.toSubmodelServiceEndpointString(getBaseEndpoint(), ismDesc.getId());
				smDesc.setEndpoints(DescriptorUtils.newEndpoints(smEp, "SUBMODEL-3.0"));
			}
			
			return smDesc;
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize SubmodelDescriptor: id=" + ismDesc.getId());
		}
	}
	
	@Override
	public String toString() {
		return String.format("InstantDescriptor[%s, %s]", this.id, this.status);
	}
}