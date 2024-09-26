package mdt.instance.jpa;

import jakarta.persistence.EntityManager;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface JpaModule {
	public EntityManager getEntityManager();
	public void setEntityManager(EntityManager em);
}
