package mdt.instance.jpa;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.base.Preconditions;

import utils.func.Try;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaProcessor {
	private final EntityManagerFactory m_emFact;
	
	public JpaProcessor(EntityManagerFactory emFact) {
		m_emFact = emFact;
	}
	
	public <M extends JpaModule,T> T get(JpaModuleFactory<M> moduleFact, Function<M,T> task) {
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		M jmodule = moduleFact.newInstance(em);
		try {
			T result = task.apply(jmodule);
			tx.commit();
			
			return result;
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			
			if ( jmodule instanceof AutoCloseable ac ) {
				Try.run(ac::close);
			}
			em.close();
		}
	}
	
	public <M extends JpaModule> void run(JpaModuleFactory<M> moduleFact, Consumer<M> task) {
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		M jmodule = moduleFact.newInstance(em);
		try {
			task.accept(jmodule);
			tx.commit();
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			
			if ( jmodule instanceof AutoCloseable ac ) {
				Try.run(ac::close);
			}
			em.close();
		}
	}
	
	
	public <T> T get(ThreadLocal<EntityManager> holder, Supplier<T> task) {
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		holder.set(em);
		try {
			T result = task.get();
			tx.commit();
			
			return result;
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			
			holder.remove();
			em.close();
		}
	}
	
	public void run(ThreadLocal<EntityManager> holder, Runnable task) {
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		holder.set(em);
		try {
			task.run();
			tx.commit();
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			
			holder.remove();
			em.close();
		}
	}
	
	
	public <J extends JpaModule, T> T get(J jmodule, Supplier<T> task) {
		Preconditions.checkArgument(jmodule == null || jmodule.getEntityManager() == null,
									"JpaModule has been allocated already: module=" + jmodule);
		
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();

		jmodule.setEntityManager(em);
		try {
			T result = task.get();
			tx.commit();
			
			return result;
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			em.close();
			
			Preconditions.checkState(em == jmodule.getEntityManager(),
									"JpaModule has been replaced: module=" + jmodule);
			jmodule.setEntityManager(null);
		}
	}
	
	public <J extends JpaModule> void run(J jmodule, Runnable task) {
		Preconditions.checkArgument(jmodule == null || jmodule.getEntityManager() == null,
									"JpaModule has been allocated already: module=" + jmodule);
		
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		jmodule.setEntityManager(em);
		try {
			task.run();
			tx.commit();
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			em.close();
			
			Preconditions.checkState(em == jmodule.getEntityManager(),
									"JpaModule has been replaced: module=" + jmodule);
			jmodule.setEntityManager(null);
		}
	}
	
	public <J extends JpaModule> void accept(J jmodule, Consumer<J> consumer) {
		Preconditions.checkArgument(jmodule == null || jmodule.getEntityManager() == null,
									"JpaModule has been allocated already: module=" + jmodule);
		
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		jmodule.setEntityManager(em);
		try {
			consumer.accept(jmodule);
			tx.commit();
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			em.close();
			
			Preconditions.checkState(em == jmodule.getEntityManager(),
									"JpaModule has been replaced: module=" + jmodule);
			jmodule.setEntityManager(null);
		}
	}
	
	
	public <T> T get(Function<EntityManager,T> task) {
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		try {
			T result = task.apply(em);
			tx.commit();
			
			return result;
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			em.close();
		}
	}
	
	public void run(Consumer<EntityManager> task) {
		EntityManager em = m_emFact.createEntityManager();
		EntityTransaction tx = em.getTransaction();
		tx.begin();
		
		try {
			task.accept(em);
			tx.commit();
		}
		finally {
			if ( tx.isActive() ) {
				tx.rollback();
			}
			em.close();
		}
	}
}
