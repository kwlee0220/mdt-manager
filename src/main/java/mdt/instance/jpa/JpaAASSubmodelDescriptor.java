package mdt.instance.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
@NoArgsConstructor
@Entity
@Table(name="aas_submodel_descriptors")
public class JpaAASSubmodelDescriptor {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name="row_id")
	private Long rowId;
	
	@Column(name="json", length=32768)
	private String json;
	
	public JpaAASSubmodelDescriptor(String json) {
		this.json = json;
	}
}
