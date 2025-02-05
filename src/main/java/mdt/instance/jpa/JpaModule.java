package mdt.instance.jpa;

import com.google.common.base.Preconditions;

import jakarta.persistence.EntityManager;


/**
 * JPA 모듈의 기본 기능을 정의한 인터페이스.
 * <p>
 * {@link EntityManager}를 이용하는 기능이 필요한 클래스는 이 인터페이스를 구현한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaModule {
	/**
	 * Returns the {@link EntityManager} used by this instance.
	 * 
	 * @return {@link EntityManager} used by this instance.
	 */
	public EntityManager getEntityManager();
	
	/**
	 * Sets the {@link EntityManager} to be used by this instance.
	 * 
	 * @param em	{@link EntityManager} to be set.
	 */
	public void setEntityManager(EntityManager em);
	
	public default void checkEntityManager() {
		Preconditions.checkNotNull(getEntityManager() != null, "EntityManager is not set");
	}
}
