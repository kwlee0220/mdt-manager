package mdt.instance.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaMDTOperationDescriptorRepository extends CrudRepository<JpaMDTOperationDescriptor, Long> {
	@Query("SELECT op FROM JpaMDTOperationDescriptor op WHERE op.instance.id = :instId")
	public List<JpaMDTOperationDescriptor> findAllByInstanceId(@Param("instId") String instId);
}
