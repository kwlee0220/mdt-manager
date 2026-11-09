package mdt.instance.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaMDTSubmodelDescriptorRepository extends CrudRepository<JpaMDTSubmodelDescriptor, Long> {
	@Query("SELECT d FROM JpaMDTSubmodelDescriptor d WHERE d.id = :id")
	public Optional<JpaMDTSubmodelDescriptor> findBySubmodelId(@Param("id") String id);
	
	@EntityGraph(attributePaths="instance")
	public List<JpaMDTSubmodelDescriptor> findAll();
	@EntityGraph(attributePaths="instance")
	public List<JpaMDTSubmodelDescriptor> findAllByIdShort(@Param("idShort") String idShort);
	@EntityGraph(attributePaths="instance")
	public List<JpaMDTSubmodelDescriptor> findAllBySemanticId(@Param("semanticId") String semanticId);
	
	public List<JpaMDTSubmodelDescriptor> findAllByInstance_InstanceId(String instanceId);
	public Optional<JpaMDTSubmodelDescriptor> findByInstance_InstanceIdAndIdShort(String instanceId, String idShort);
}