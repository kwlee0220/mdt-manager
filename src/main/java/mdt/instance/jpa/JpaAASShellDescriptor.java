package mdt.instance.jpa;

import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Entity
@Table(name="aas_shell_descriptors")
public class JpaAASShellDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;
	
	// Hibernate 6의 기본 JSON 매핑(Jackson으로 직렬화)으로 PostgreSQL jsonb 컬럼에 저장한다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> attrs;     // 또는 커스텀 POJO
	
	public Long getRowId() {
		return rowId;
	}
	
	public void setRowId(Long rowId) {
		this.rowId = rowId;
	}
	
	public Map<String, Object> getAttrs() {
		return attrs;
	}
	
	public void setAttrs(Map<String, Object> attrs) {
		this.attrs = attrs;
	}
}