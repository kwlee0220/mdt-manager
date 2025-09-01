package mdt.instance.jpa;

import java.util.Map;

import org.hibernate.annotations.Type;

import com.vladmihalcea.hibernate.type.json.JsonType;

import lombok.Getter;
import lombok.Setter;

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
@Getter @Setter
@Entity
@Table(name="aas_shell_descriptors")
public class JpaAASShellDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;
	
	@Type(JsonType.class)                  // 핵심
	@Column(columnDefinition = "jsonb")    // PostgreSQL 타입 지정
	private Map<String, Object> attrs;     // 또는 커스텀 POJO
}