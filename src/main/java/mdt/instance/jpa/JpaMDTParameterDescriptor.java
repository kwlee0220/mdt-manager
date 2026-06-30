package mdt.instance.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import mdt.model.instance.MDTParameterDescriptor;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
@Table(name="parameter_descriptors")
public class JpaMDTParameterDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name="instance_row_id")
	private JpaInstanceDescriptor instance;
	
	@Column(name="id", length=255)
	private String id;
	
	@Column(name="name", length=255)
	private String name;
	
	@Column(name="valueType", length=64)
	private String valueType;
	
	@Column(name="reference", length=255)
	private String reference;
	
	public Long getRowId() {
		return rowId;
	}
	
	public void setRowId(Long rowId) {
		this.rowId = rowId;
	}
	
	public JpaInstanceDescriptor getInstance() {
		return instance;
	}
	
	public void setInstance(JpaInstanceDescriptor instance) {
		this.instance = instance;
	}
	
	public String getId() {
		return id;
	}
	
	public void setId(String id) {
		this.id = id;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getValueType() {
		return valueType;
	}
	
	public void setValueType(String valueType) {
		this.valueType = valueType;
	}
	
	public String getReference() {
		return reference;
	}
	
	public void setReference(String reference) {
		this.reference = reference;
	}
	
	@Override
	public String toString() {
		String nameStr = "";
		if ( this.name != null ) {
			nameStr = String.format("%s, ", this.name);
		}
		return String.format("%s(%s%s): %s", this.id, nameStr, this.valueType, this.reference);
	}
	
	static JpaMDTParameterDescriptor from(MDTParameterDescriptor desc) {
		JpaMDTParameterDescriptor jpaDesc = new JpaMDTParameterDescriptor();
		jpaDesc.setId(desc.getId());
		jpaDesc.setName(desc.getName());
		jpaDesc.setValueType(desc.getValueType());
		jpaDesc.setReference(desc.getReference());
		
		return jpaDesc;
	}
	
	public MDTParameterDescriptor toMDTParameterDescriptor() {
		return new MDTParameterDescriptor(id, name, valueType, reference);
	}
}
