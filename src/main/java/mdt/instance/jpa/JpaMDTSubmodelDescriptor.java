package mdt.instance.jpa;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.model.Key;
import org.eclipse.digitaltwin.aas4j.v3.model.Reference;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;

import com.google.common.base.Preconditions;

import lombok.Getter;
import lombok.Setter;

import utils.InternalException;
import utils.func.FOption;

import mdt.model.DescriptorUtils;
import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTSubmodelDescriptor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Entity
@Table(
	name="submodel_descriptors",
	indexes = {
		@Index(name="submodel_id_idx", columnList="id", unique=true),
		@Index(name="submodel_idshort_idx", columnList="id_short")
})
public class JpaMDTSubmodelDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;

	@ManyToOne(fetch = FetchType.EAGER)
	@JoinColumn(name="instance_row_id")
	private JpaInstanceDescriptor instance;
	
	@Column(name="id", length=255)
	private String id;
	
	@Column(name="id_short", length=64)
	private String idShort;
	
	@Column(name="semanticId", length=255)
	private String semanticId;
	
	@Column(columnDefinition = "bytea", nullable = false)
	private byte[] aasSubmodelDescriptorJsonBytes;

	public static JpaMDTSubmodelDescriptor from(Submodel submodel) throws SerializationException {
		SubmodelDescriptor smDesc = DescriptorUtils.createSubmodelDescriptor(submodel, null);
		return from(smDesc);
	}

	public static JpaMDTSubmodelDescriptor from(SubmodelDescriptor smDesc) throws SerializationException {
		JpaMDTSubmodelDescriptor desc = new JpaMDTSubmodelDescriptor();
		
		desc.id = smDesc.getId();
		desc.idShort = smDesc.getIdShort();
		desc.setSemanticIdReference(smDesc.getSemanticId());
		desc.setSubmodelDescriptor(smDesc);
		
		return desc;
	}
	
	public SubmodelDescriptor getAASSubmodelDescriptor() {
		try {
			String json = new String(this.aasSubmodelDescriptorJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer().read(json, SubmodelDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize SubmodelDescriptor: " + e);
		}
	}
	
	void setSubmodelDescriptor(SubmodelDescriptor smDesc) {
		Preconditions.checkArgument(smDesc != null, "SubmodelDescriptor must not be null");
		
		try {
			this.aasSubmodelDescriptorJsonBytes = MDTModelSerDe.getJsonSerializer().write(smDesc)
																.getBytes(StandardCharsets.UTF_8);
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to serialize AAS Descriptor: " + e);
		}
	}
	
	public void setSemanticIdReference(Reference semanticId) {
		if ( semanticId != null  /*&& semanticId.getType() == ReferenceTypes.EXTERNAL_REFERENCE*/ ) {
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
		setSubmodelDescriptor(smDesc);
	}
	
	public MDTSubmodelDescriptor toMDTSubmodelDescriptor() {
		return new MDTSubmodelDescriptor(getId(), getIdShort(), getSemanticId());
	}
	
	@Override
	public String toString() {
		return String.format("JpaMDTSubmodelDescriptor[%s]", this.idShort);
	}
}