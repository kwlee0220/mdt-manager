package mdt.instance.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import mdt.model.instance.MDTInstanceStatus;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaInstanceDescriptorRepository extends CrudRepository<JpaInstanceDescriptor, Long> {
//	@Query("SELECT d FROM JpaInstanceDescriptor d WHERE d.id = :instId")
	public Optional<JpaInstanceDescriptor> findByInstanceId(String instId);
	
//	@Query("SELECT d FROM JpaInstanceDescriptor d ORDER BY d.rowId")
//	public Iterable<JpaInstanceDescriptor> findAll();
//	@Query("SELECT d FROM JpaInstanceDescriptor d ORDER BY d.rowId")
//	public Iterable<JpaInstanceDescriptor> findAllOrderByRowId();

	public Optional<JpaInstanceDescriptor> findByAasId(String aasId);
	public Iterable<JpaInstanceDescriptor> findAllByAasIdShort(String aasIdShort);
	public Iterable<JpaInstanceDescriptor> findAllByGlobalAssetId(String assetId);
	
	// 인스턴스 삭제에 JPQL bulk DELETE를 쓰지 않는다. cascade를 우회해 자식 행 FK 위반·orphan을 만들 수 있다.
	
	@Modifying
	@Transactional
	@Query("UPDATE JpaInstanceDescriptor d SET d.status = 'STOPPED', d.baseEndpoint = null")
	public int resetAll();
	
	/**
	 * 상태가 {@code from}일 때만 {@code to}로 바꾼다(조건부 UPDATE, 원자적). endpoint는 지운다.
	 *
	 * @return	바뀐 행 수(0 또는 1).
	 */
	@Modifying
	@Transactional
	@Query("UPDATE JpaInstanceDescriptor d SET d.status = :to, d.baseEndpoint = null "
			+ "WHERE d.instanceId = :instId AND d.status = :from")
	public int compareAndSetStatus(@Param("instId") String instId, @Param("from") MDTInstanceStatus from,
									@Param("to") MDTInstanceStatus to);
}