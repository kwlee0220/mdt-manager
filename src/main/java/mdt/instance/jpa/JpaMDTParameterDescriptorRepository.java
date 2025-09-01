package mdt.instance.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaMDTParameterDescriptorRepository extends CrudRepository<JpaMDTParameterDescriptor, Long> {
	@Query("SELECT d FROM JpaMDTParameterDescriptor d WHERE d.instance.id = :instId")
	public List<JpaMDTParameterDescriptor> findAllByInstanceId(@Param("instId") String instId);
}
