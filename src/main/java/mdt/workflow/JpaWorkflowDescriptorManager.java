package mdt.workflow;

import java.util.Collection;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;

import com.fasterxml.jackson.core.JsonProcessingException;

import utils.InternalException;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import mdt.instance.jpa.JpaModule;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;
import mdt.model.workflow.BuiltInTaskTemplates;
import mdt.model.workflow.MDTWorkflowManager;
import mdt.model.workflow.descriptor.TaskTemplateDescriptor;
import mdt.model.workflow.descriptor.WorkflowDescriptor;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JpaWorkflowDescriptorManager implements MDTWorkflowManager, JpaModule {
	private volatile EntityManager m_em;

	public JpaWorkflowDescriptorManager() {
		this(null);
	}
	public JpaWorkflowDescriptorManager(EntityManager em) {
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
	public WorkflowDescriptor getWorkflowDescriptor(String id) throws ResourceNotFoundException {
		return getJpaWorkflowDescriptor(id).getWorkflowDescriptor();
	}

	@Override
	public List<WorkflowDescriptor> getWorkflowDescriptorAll() {
		return m_em.createQuery("select d from JpaWorkflowDescriptor d", JpaWorkflowDescriptor.class)
					.getResultStream()
					.map(JpaWorkflowDescriptor::getWorkflowDescriptor)
					.toList();
	}

	@Override
	public String addWorkflowDescriptor(WorkflowDescriptor desc) throws ResourceAlreadyExistsException {
		try {
			m_em.persist(new JpaWorkflowDescriptor(desc));
			return desc.getId();
		}
		catch ( EntityExistsException e ) {
			throw new ResourceAlreadyExistsException("WorkflowDescriptor", "id=" + desc.getId());
		}
	}
	
	public String addWorkflowDescriptor(String jsonDesc) throws ResourceAlreadyExistsException {
		try {
			JpaWorkflowDescriptor jdesc = new JpaWorkflowDescriptor(jsonDesc);
			try {
				m_em.persist(jdesc);
				
				return jdesc.getId();
			}
			catch ( EntityExistsException e ) {
				throw new ResourceAlreadyExistsException("WorkflowDescriptor", "json=" + jsonDesc);
			}
			catch ( ConstraintViolationException e ) {
				switch ( e.getKind() ) {
					case UNIQUE:
						throw new ResourceAlreadyExistsException("WorkflowDescriptor", "id=" + jdesc.getId());
					default:
						throw new InternalException("" + e);
				}
			}
		}
		catch ( JsonProcessingException e ) {
			String msg = String.format("Failed to parse input WorkflowDescriptor in Json: %s", e);
			throw new IllegalArgumentException(msg);
		}
	}

	@Override
	public void removeWorkflowDescriptor(String id) throws ResourceNotFoundException {
		JpaWorkflowDescriptor jdesc = getJpaWorkflowDescriptor(id);
		if ( jdesc != null ) {
			m_em.remove(jdesc);
		}
		else {
			throw new ResourceNotFoundException("WorkflowDescriptor", "id=" + id);
		}
	}
	
	public void removeWorkflowDescriptorAll() {
		m_em.createQuery("delete from JpaWorkflowDescriptor").executeUpdate();
	}

	public JpaWorkflowDescriptor getJpaWorkflowDescriptor(String id) throws ResourceNotFoundException {
		String sql = "select desc from JpaWorkflowDescriptor desc where desc.id = ?1";
		TypedQuery<JpaWorkflowDescriptor> query = m_em.createQuery(sql, JpaWorkflowDescriptor.class);
		query.setParameter(1, id);
		try {
			return query.getSingleResult();
		}
		catch ( NoResultException expected ) {
			throw new ResourceNotFoundException("WorkflowDescriptor", "id=" + id);
		}
	}
	
	@Override
	public TaskTemplateDescriptor getBuiltInTaskTemplate(String id) throws ResourceNotFoundException {
		TaskTemplateDescriptor tmplt = BuiltInTaskTemplates.get(id);
		if ( tmplt != null ) {
			return tmplt;
		}
		
		throw new ResourceNotFoundException("BuiltInTaskTemplate", "id=" + id);
	}
	@Override
	public Collection<TaskTemplateDescriptor> getBuiltInTaskTemplateAll() {
		return BuiltInTaskTemplates.getAll();
	}
}
