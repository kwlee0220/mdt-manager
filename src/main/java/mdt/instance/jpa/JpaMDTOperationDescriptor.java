package mdt.instance.jpa;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;

import lombok.Getter;
import lombok.Setter;

import utils.InternalException;
import utils.func.FOption;
import utils.stream.FStream;

import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTOperationDescriptor;
import mdt.model.instance.MDTOperationDescriptor.ArgumentDescriptor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
@Table(name="operation_descriptors")
@Getter @Setter
public class JpaMDTOperationDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name="instance_row_id")
	private JpaInstanceDescriptor instance;
	
	@Column(name="id", length=255)
	private String id;
	
	@Column(name="operationType", length=64)
	private String operationType;
	
	@Column(columnDefinition = "bytea", nullable = false)
	private byte[] inputArgumentsJsonBytes;
	
	@Column(columnDefinition = "bytea", nullable = false)
	private byte[] outputArgumentsJsonBytes;
	
	public List<ArgumentDescriptor> getInputArguments() {
		if ( this.inputArgumentsJsonBytes == null ) {
			return Collections.emptyList();
		}
		
		try {
			String json = new String(this.inputArgumentsJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer().readList(json, ArgumentDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize SubmodelDescriptor: " + e);
		}
	}
	
	public void setInputArguments(List<ArgumentDescriptor> inArgs) {
		try {
			String json = MDTModelSerDe.getJsonSerializer().write(inArgs);
			this.inputArgumentsJsonBytes = json.getBytes(StandardCharsets.UTF_8);
		}
		catch ( Exception e ) {
			throw new InternalException("Failed to serialize inputArguments: " + e);
		}
	}
	
	public List<ArgumentDescriptor> getOutputArguments() {
		if ( this.outputArgumentsJsonBytes == null ) {
			return Collections.emptyList();
		}
		
		try {
			String json = new String(this.outputArgumentsJsonBytes, StandardCharsets.UTF_8);
			return MDTModelSerDe.getJsonDeserializer().readList(json, ArgumentDescriptor.class);
		}
		catch ( DeserializationException e ) {
			throw new InternalException("Failed to deserialize SubmodelDescriptor: " + e);
		}
	}
	
	public void setOutputArguments(List<ArgumentDescriptor> outArgs) {
		try {
			String json = MDTModelSerDe.getJsonSerializer().write(outArgs);
			this.outputArgumentsJsonBytes = json.getBytes(StandardCharsets.UTF_8);
		}
		catch ( Exception e ) {
			throw new InternalException("Failed to serialize outputArguments: " + e);
		}
	}
	
	public MDTOperationDescriptor toMDTOperationDescriptor() {
		return new MDTOperationDescriptor(id, operationType, getInputArguments(), getOutputArguments());
	}
	
	@Override
	public String toString() {
		String id = FOption.getOrElse(this.id, "?");
		String inArgs = FStream.from(this.getInputArguments()).map(ArgumentDescriptor::getId).join(", ");
		String outArgs = FStream.from(this.getOutputArguments()).map(ArgumentDescriptor::getId).join(", ");
		return String.format("%s[%s]: (%s) -> (%s)", this.operationType, id, inArgs, outArgs);
	}
	
	static JpaMDTOperationDescriptor from(MDTOperationDescriptor desc) {
		JpaMDTOperationDescriptor jpaDesc = new JpaMDTOperationDescriptor();
		jpaDesc.setId(desc.getId());
		jpaDesc.setOperationType(null);
		
		return jpaDesc;
	}
}
