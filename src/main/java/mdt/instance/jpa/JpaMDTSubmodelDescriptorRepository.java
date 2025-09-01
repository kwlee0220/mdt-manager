package mdt.instance.jpa;

import java.util.List;
import java.util.Optional;

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
	
	public List<JpaMDTSubmodelDescriptor> findAll();
	public List<JpaMDTSubmodelDescriptor> findAllByIdShort(@Param("idShort") String idShort);
	public List<JpaMDTSubmodelDescriptor> findAllBySemanticId(@Param("semanticId") String semanticId);
	
	@Query("select sm from JpaMDTSubmodelDescriptor sm join fetch sm.instance instance where instance.id = :instanceId")
	public List<JpaMDTSubmodelDescriptor> findAllByInstanceId(@Param("instanceId") String instanceId);
}