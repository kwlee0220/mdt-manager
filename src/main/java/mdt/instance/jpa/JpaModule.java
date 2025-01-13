package mdt.instance.jpa;

import com.google.common.base.Preconditions;

import jakarta.persistence.EntityManager;


/**
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
