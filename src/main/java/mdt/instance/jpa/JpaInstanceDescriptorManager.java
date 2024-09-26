package mdt.instance.jpa;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import mdt.instance.InstanceDescriptorManager;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.instance.MDTInstanceManagerException;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaInstanceDescriptorManager implements InstanceDescriptorManager, JpaModule {
	private volatile EntityManager m_em;

	public JpaInstanceDescriptorManager() {
		this(null);
	}
	public JpaInstanceDescriptorManager(EntityManager em) {
		m_em = em;
	}
	
	@Override
	public EntityManager getEntityManager() {
		return m_em;
	}
	
	@Override
	public void setEntityManager(EntityManager em) {
		m_em = em;
	}

	@Override
	public long count() {
		String jpql = "select count(d) from JpaInstanceDescriptor d";
		return m_em.createQuery(jpql, Long.class).getSingleResult();
	}

	@Override
	public JpaInstanceDescriptor getInstanceDescriptor(String id) throws MDTInstanceManagerException {
		String sql = "select d from JpaInstanceDescriptor d where id = :id";
		TypedQuery<JpaInstanceDescriptor> query = m_em.createQuery(sql, JpaInstanceDescriptor.class);
		query.setParameter("id", id);
		try {
			return query.getSingleResult();
		}
		catch ( NoResultException expected ) {
			return null;
		}
	}

	@Override
	public List<JpaInstanceDescriptor> getInstanceDescriptorAll() throws MDTInstanceManagerException {
		return m_em.createQuery("select d from JpaInstanceDescriptor d",
								JpaInstanceDescriptor.class).getResultList();
	}
	
	public JpaInstanceDescriptor getInstanceDescriptorByAasId(String aasId) throws MDTInstanceManagerException {
		String jpql = "select d from JpaInstanceDescriptor d where d.aasId = :aasId";
		TypedQuery<JpaInstanceDescriptor> query =  m_em.createQuery(jpql, JpaInstanceDescriptor.class);
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
		String jpql = "select d from JpaInstanceDescriptor d where d.aasIdShort = ?1";
		return m_em.createQuery(jpql, JpaInstanceDescriptor.class)
					.setParameter(1, aasIdShort)
					.getResultList();
	}

	@Override
	public List<JpaInstanceDescriptor> findInstanceDescriptorAll(String filterExpr) {
		boolean containsSubmodelExpr = filterExpr.toLowerCase().contains("submodel.");
		String sql = (containsSubmodelExpr)
					? "select distinct instance from JpaInstanceDescriptor instance "
						+ "join fetch instance.submodels as submodel where " + filterExpr
					: "select instance from JpaInstanceDescriptor instance where " + filterExpr;
		TypedQuery<JpaInstanceDescriptor> query = m_em.createQuery(sql, JpaInstanceDescriptor.class);
		return query.getResultList();
	}

	@Override
	public <S> List<S> query(SearchCondition cond, InstanceDescriptorTransform<S> transform) {
		return transform.apply(cond.apply(m_em));
	}
	

	@Override
	public void addInstanceDescriptor(JpaInstanceDescriptor desc)
		throws MDTInstanceManagerException, ResourceAlreadyExistsException {
		try {
			m_em.persist(desc);
		}
		catch ( EntityExistsException e ) {
			throw new ResourceAlreadyExistsException("MDTInstance", "id=" + desc.getId());
		}
	}

	@Override
	public void removeInstanceDescriptor(String id) throws MDTInstanceManagerException {
		JpaInstanceDescriptor desc = getInstanceDescriptor(id);
		m_em.remove(desc);
	}

	@Override
	public JpaInstanceDescriptor updateInstanceDescriptor(JpaInstanceDescriptor desc) {
		JpaInstanceDescriptor old = getInstanceDescriptor(desc.getId());
		if ( old == null ) {
			return null;
		}
		
		old.setAasId(desc.getAasId());
		old.setAasIdShort(desc.getAasIdShort());
		old.setGlobalAssetId(desc.getGlobalAssetId());
		old.setAssetType(desc.getAssetType());
		old.setArguments(desc.getArguments());
		
		return old;
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
