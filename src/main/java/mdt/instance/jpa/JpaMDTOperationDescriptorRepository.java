package mdt.instance.jpa;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaMDTOperationDescriptorRepository extends CrudRepository<JpaMDTOperationDescriptor, Long> {
	public List<JpaMDTOperationDescriptor> findAllByInstance_InstanceId(String instId);
}
