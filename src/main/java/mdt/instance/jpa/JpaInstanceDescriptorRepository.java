package mdt.instance.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaInstanceDescriptorRepository extends CrudRepository<JpaInstanceDescriptor, Long> {
	@Query("SELECT d FROM JpaInstanceDescriptor d WHERE d.id = :instId")
	public Optional<JpaInstanceDescriptor> findByInstanceId(@Param("instId") String instId);
	
	@Query("SELECT d FROM JpaInstanceDescriptor d ORDER BY d.rowId")
	public Iterable<JpaInstanceDescriptor> findAll();

	public Optional<JpaInstanceDescriptor> findByAasId(String aasId);
	public Iterable<JpaInstanceDescriptor> findAllByAasIdShort(String aasIdShort);
	public Iterable<JpaInstanceDescriptor> findAllByGlobalAssetId(String assetId);
	
	@Modifying
	@Transactional
	@Query("DELETE FROM JpaInstanceDescriptor d WHERE d.id = :instId")
	public void deleteByInstanceId(@Param("instId") String instId);
	
	@Modifying
	@Transactional
	@Query("UPDATE JpaInstanceDescriptor d SET d.status = 'STOPPED', d.baseEndpoint = null WHERE d.id = :instId")
	public void markStoppedOfInstanceId(@Param("instId") String instId);
	
	@Modifying
	@Transactional
	@Query("UPDATE JpaInstanceDescriptor d SET d.status = 'STOPPED', d.baseEndpoint = null")
	public int resetAll();
}