package mdt.instance.jpa;

import java.util.List;

import org.springframework.data.repository.CrudRepository;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaMDTParameterDescriptorRepository extends CrudRepository<JpaMDTParameterDescriptor, Long> {
	public List<JpaMDTParameterDescriptor> findAllByInstance_InstanceId(String instId);
}
