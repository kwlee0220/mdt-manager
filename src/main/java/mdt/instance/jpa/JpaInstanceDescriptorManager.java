package mdt.instance.jpa;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.hibernate.exception.ConstraintViolationException;

import com.google.common.base.Preconditions;

import utils.jpa.JpaSession;

import mdt.instance.InstanceDescriptorManager;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceManagerException;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaInstanceDescriptorManager implements InstanceDescriptorManager {
	private final JpaSession m_session;

	public JpaInstanceDescriptorManager(JpaSession session) {
		Preconditions.checkArgument(session != null, "JpaSession is null");
		m_session = session;
	}

	@Override
	public long count() {
		EntityManager em = checkEntityManager();
		
		String jpql = "select count(d) from JpaInstanceDescriptor d";
		return em.createQuery(jpql, Long.class).getSingleResult();
	}

	@Override
	public JpaInstanceDescriptor getInstanceDescriptor(String id) throws MDTInstanceManagerException {
		EntityManager em = checkEntityManager();
		
		String sql = "select d from JpaInstanceDescriptor d where id = :id";
		TypedQuery<JpaInstanceDescriptor> query = em.createQuery(sql, JpaInstanceDescriptor.class);
		query.setParameter("id", id);
		try {
			return query.getSingleResult();
		}
		catch ( NoResultException expected ) {
			return null;
		}
	}

	@Override
	public List<JpaInstanceDescriptor> getInstanceDescriptorAll() {
		EntityManager em = checkEntityManager();
		
		return em.createQuery("select d from JpaInstanceDescriptor d order by rowId",
								JpaInstanceDescriptor.class).getResultList();
	}
	
	public JpaInstanceDescriptor getInstanceDescriptorByAasId(String aasId) throws MDTInstanceManagerException {
		EntityManager em = checkEntityManager();
		
		String jpql = "select d from JpaInstanceDescriptor d where d.aasId = :aasId";
		TypedQuery<JpaInstanceDescriptor> query =  em.createQuery(jpql, JpaInstanceDescriptor.class);
		query.setParameter("aasId", aasId);
		try {
			return query.getSingleResult();
		}
		catch ( NoResultException expected ) {
			return null;
		}
	}
	public List<JpaInstanceDescriptor> getInstanceDescriptorAllByAasIdShort(String aasIdShort)
		throws MDTInstanceManagerException {
		EntityManager em = checkEntityManager();
		
		String jpql = "select d from JpaInstanceDescriptor d where d.aasIdShort = ?1";
		return em.createQuery(jpql, JpaInstanceDescriptor.class)
				.setParameter(1, aasIdShort)
				.getResultList();
	}
	public List<JpaInstanceDescriptor> getInstanceDescriptorAllByAssetId(String assetId)
		throws MDTInstanceManagerException {
		EntityManager em = checkEntityManager();
		
		String jpql = "select d from JpaInstanceDescriptor d where d.globalAssetId = ?1";
		return em.createQuery(jpql, JpaInstanceDescriptor.class)
				.setParameter(1, assetId)
				.getResultList();
	}

	@Override
	public List<JpaInstanceDescriptor> findInstanceDescriptorAll(String filterExpr) {
		EntityManager em = checkEntityManager();
		
		boolean containsSubmodelExpr = filterExpr.toLowerCase().contains("submodel.");
		String sql = (containsSubmodelExpr)
					? "select distinct instance from JpaInstanceDescriptor instance "
						+ "join fetch instance.submodels as submodel where " + filterExpr
					: "select instance from JpaInstanceDescriptor instance where " + filterExpr;
		TypedQuery<JpaInstanceDescriptor> query = em.createQuery(sql, JpaInstanceDescriptor.class);
		return query.getResultList();
	}

	@Override
	public <S> List<S> query(SearchCondition cond, InstanceDescriptorTransform<S> transform) {
		EntityManager em = checkEntityManager();
		
		return transform.apply(cond.apply(em));
	}
	

	@Override
	public void addInstanceDescriptor(InstanceDescriptor desc)
		throws MDTInstanceManagerException, ResourceAlreadyExistsException {
		EntityManager em = checkEntityManager();
		
		try {
			em.persist(desc);
		}
		catch ( EntityExistsException | ConstraintViolationException e ) {
			throw new ResourceAlreadyExistsException("MDTInstance", "id=" + desc.getId());
		}
	}
	
	public void updateInstanceDescriptor(JpaInstanceDescriptor desc) throws MDTInstanceManagerException {
		EntityManager em = checkEntityManager();
        
		em.merge(desc);
	}

	@Override
	public void removeInstanceDescriptor(String id) throws MDTInstanceManagerException {
		EntityManager em = checkEntityManager();
		
		JpaInstanceDescriptor desc = getInstanceDescriptor(id);
		em.remove(desc);
	}
	
	private EntityManager checkEntityManager() {
//		Preconditions.checkState(m_session.getEntityManager().isOpen(), "JpaSession is closed");
		return m_session.getEntityManager();
	}
	
	
	public interface SearchCondition extends Function<EntityManager,Stream<JpaInstanceDescriptor>> { };
	
	public static class JPQLSearch implements SearchCondition {
		private final String m_jpql;
		
		public JPQLSearch(String jpql) {
			m_jpql = jpql;
		}

		@Override
		public Stream<JpaInstanceDescriptor> apply(EntityManager em) {
			TypedQuery<JpaInstanceDescriptor> query = em.createQuery(m_jpql, JpaInstanceDescriptor.class);
			return query.getResultStream();
		}
	}
	public static final SearchAll SEARCH_ALL = new SearchAll();
	private static class SearchAll implements SearchCondition {
		@Override
		public Stream<JpaInstanceDescriptor> apply(EntityManager em) {
			return em.createQuery("select d from JpaInstanceDescriptor d", JpaInstanceDescriptor.class)
						.getResultStream();
		}
	}
	public static class FilterSearch implements SearchCondition {
		private final String m_filterExpr;
		private final boolean m_containsSubmodelExpr;
		
		public FilterSearch(String filterExpr, boolean containsSubmodelExpr) {
			m_filterExpr = filterExpr;
			m_containsSubmodelExpr = containsSubmodelExpr;
		}
		public FilterSearch(String filterExpr) {
			m_filterExpr = filterExpr;
			m_containsSubmodelExpr = false;
		}

		@Override
		public Stream<JpaInstanceDescriptor> apply(EntityManager em) {
			String jpql = (m_containsSubmodelExpr)
						? "select distinct instance from JpaInstanceDescriptor instance "
							+ "join fetch instance.submodels as submodel where " + m_filterExpr
						: "select instance from JpaInstanceDescriptor instance where " + m_filterExpr;
			
			TypedQuery<JpaInstanceDescriptor> query = em.createQuery(jpql, JpaInstanceDescriptor.class);
			return query.getResultStream();
		}
	}
	
	public interface InstanceDescriptorTransform<T> extends Function<Stream<JpaInstanceDescriptor>,List<T>> { };
	public static final InstanceDescriptorTransform<JpaInstanceDescriptor> IDENTITY_TRANSFORM = Stream::toList;
}
