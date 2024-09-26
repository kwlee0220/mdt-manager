package mdt.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import mdt.model.AASUtils;
import mdt.model.workflow.descriptor.WorkflowDescriptor;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@Entity
@Table(
	name="workflow_descriptors",
	indexes = {
		@Index(name="id_idx", columnList="id", unique=true)
	})
public class JpaWorkflowDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id") private Long rowId;

	@Column(name="id", length=64, unique=true) private String id;
	@Lob @Column(name="json_descriptor") private String jsonDescriptor;
	@Transient private WorkflowDescriptor m_wfDesc = null;
	
	@SuppressWarnings("unused")
	private JpaWorkflowDescriptor() { }
	
	public JpaWorkflowDescriptor(WorkflowDescriptor wfDesc) {
		id = wfDesc.getId();
		jsonDescriptor = AASUtils.writeJson(wfDesc);
		m_wfDesc = wfDesc;
	}
	
	public JpaWorkflowDescriptor(String wfDescString) throws JsonProcessingException {
		WorkflowDescriptor wfDesc = WorkflowDescriptor.parseJsonString(wfDescString);
		
		id = wfDesc.getId();
		jsonDescriptor = wfDescString;
		m_wfDesc = wfDesc;
	}
	
//	public String getId() {
//		return this.id;
//	}
//	@SuppressWarnings("unused")
//	private void setId(String id) {
//		this.id = id;
//	}
//	
//	public String getJsonDescriptor() {
//		return this.jsonDescriptor;
//	}
//	@SuppressWarnings("unused")
//	private void setJsonDescriptor(String desc) {
//		jsonDescriptor = desc;
//	}
	
	public WorkflowDescriptor getWorkflowDescriptor() {
		if ( m_wfDesc == null ) {
			m_wfDesc = parseJson();
		}
		return m_wfDesc;
	}
	
	private WorkflowDescriptor parseJson() {
		return AASUtils.readJson(jsonDescriptor, WorkflowDescriptor.class);
	}
}