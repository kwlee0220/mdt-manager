package mdt.instance.jpa;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.Key;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.ReferenceTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;

import com.google.common.base.Preconditions;

import lombok.Getter;
import lombok.Setter;

import utils.func.FOption;

import mdt.model.DescriptorUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.instance.InstanceSubmodelDescriptor;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Entity
@Table(
	name="instance_submodel_descriptors",
	indexes = {
		@Index(name="submodel_id_idx", columnList="id", unique=true),
		@Index(name="submodel_idshort_idx", columnList="id_short")
	})
public class JpaInstanceSubmodelDescriptor implements InstanceSubmodelDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name="instance_row_id")
	private JpaInstanceDescriptor instance;
	
	@Column(name="id", length=255)
	private String id;
	
	@Column(name="id_short", length=64)
	private String idShort;
	
	@Column(name="semanticId", length=255)
	private String semanticId;

	@OneToOne(fetch=FetchType.LAZY, cascade=CascadeType.ALL, orphanRemoval=true)
	@JoinColumn(name="aas_submodel_descriptor_id")
	private JpaAASSubmodelDescriptor aasSubmodelDescriptor;

	public static JpaInstanceSubmodelDescriptor from(Submodel submodel) throws SerializationException {
		SubmodelDescriptor smDesc = DescriptorUtils.createSubmodelDescriptor(submodel, null);
		return from(smDesc);
	}

	public static JpaInstanceSubmodelDescriptor from(SubmodelDescriptor smDesc) throws SerializationException {
		JpaInstanceSubmodelDescriptor desc = new JpaInstanceSubmodelDescriptor();
		
		desc.id = smDesc.getId();
		desc.idShort = smDesc.getIdShort();
		desc.setSemanticIdReference(smDesc.getSemanticId());
		desc.aasSubmodelDescriptor = new JpaAASSubmodelDescriptor(MDTModelSerDe.getJsonSerializer().write(smDesc));
		
		return desc;
	}
	
	public String getJson() {
		return this.aasSubmodelDescriptor.getJson();
	}
	
	public void setSemanticIdReference(Reference semanticId) {
		if ( semanticId != null  && semanticId.getType() == ReferenceTypes.EXTERNAL_REFERENCE ) {
			List<Key> keys = semanticId.getKeys();
			if ( keys.size() == 1 ) {
				setSemanticId(keys.get(0).getValue());
			}
			else if ( keys.size() == 0 ) { }
			else {
				throw new IllegalArgumentException("Unexpected semanticId Reference: " + semanticId);
			}
		}
	}
	
	public void updateFrom(SubmodelDescriptor smDesc) {
		Preconditions.checkArgument(getId().equals(smDesc.getId()));
		
		setIdShort(smDesc.getIdShort());
		FOption.accept(smDesc.getSemanticId(), this::setSemanticIdReference);
		
		try {
			getAasSubmodelDescriptor().setJson(MDTModelSerDe.getJsonSerializer().write(smDesc));
		}
		catch ( SerializationException e ) {
			throw new IllegalArgumentException("Failed to JSON-serialize SubmodelDescriptor: desc=" + smDesc);
		}
	}
}