package mdt.repository;

import java.util.List;

import javax.xml.datatype.Duration;

import org.eclipse.digitaltwin.aas4j.v3.model.BaseOperationResult;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationHandle;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationResult;
import org.eclipse.digitaltwin.aas4j.v3.model.OperationVariable;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElementCollection;

import com.google.common.base.Preconditions;

import utils.Indexed;
import utils.func.Funcs;
import utils.stream.FStream;

import mdt.model.InvalidIdShortPathException;
import mdt.model.ResourceNotFoundException;
import mdt.model.repository.SubmodelRepository;
import mdt.model.resource.value.SubmodelElementValue;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class InMemorySubmodelService implements SubmodelServiceProvider {
	private final SubmodelRepository m_repository;
	private Submodel m_submodel;
	private boolean m_dirty = false;
	
	public InMemorySubmodelService(SubmodelRepository repo, Submodel submodel) {
		m_repository = repo;
		m_submodel = submodel;
	}

	@Override
	public void close() throws Exception {
		if ( m_dirty ) {
			m_repository.putSubmodelById(m_submodel);
			m_dirty = false;
		}
	}

	@Override
	public Submodel getSubmodel() {
		return m_submodel;
	}

	@Override
	public Submodel putSubmodel(Submodel submodel) {
		m_submodel = submodel;
		m_dirty = true;
		return submodel;
	}

	@Override
	public List<SubmodelElement> getAllSubmodelElements() {
		return m_submodel.getSubmodelElements();
	}

	@Override
	public SubmodelElement getSubmodelElementByPath(String idShortPath) {
		return FStream.from(m_submodel.getSubmodelElements())
						.findFirst(sme -> idShortPath.equals(sme.getIdShort()))
						.getOrThrow(() -> new ResourceNotFoundException("SubmodelElement", idShortPath));
	}

	@Override
	public SubmodelElement postSubmodelElement(SubmodelElement element) {
		m_submodel.getSubmodelElements().add(element);
		m_dirty = true;
		return element;
	}

	@Override
	public SubmodelElement postSubmodelElementByPath(String idShortPath, SubmodelElement element) {
		String parentIdShortPath = FStream.of(idShortPath.split("."))
											.dropLast(1)
											.join('.');
		SubmodelElement sme = traverse(m_submodel, parentIdShortPath);
		if ( sme instanceof SubmodelElementCollection smec ) {
			List<SubmodelElement> values = smec.getValue();
			values.add(element);
			smec.setValue(values);
			m_dirty = true;
			
			return element;
		}
		else {
			throw new ResourceNotFoundException("SubmodelElement", idShortPath);
		}
	}

	@Override
	public SubmodelElement putSubmodelElementByPath(String idShortPath, SubmodelElement element) {
		String[] pathSegs = idShortPath.split(".");
		String parentIdShortPath = FStream.of(pathSegs)
											.dropLast(1)
											.join('.');
		
		String lastPathSeg = pathSegs[pathSegs.length-1];
		SubmodelElement parent = traverse(m_submodel, parentIdShortPath);
		if ( parent instanceof SubmodelElementCollection smec ) {
			List<SubmodelElement> values = smec.getValue();
			int idx = Funcs.findFirstIndexed(values, sme -> sme.getIdShort().equals(lastPathSeg))
							.map(Indexed::index)
							.getOrNull();
			if ( idx >= 0 ) {
				values.set(idx, element);
				m_dirty = true;
				return element;
			}
			else {
				throw new InvalidIdShortPathException(idShortPath, "Invalid IdShortPath");
			}
		}
		else {
			throw new InvalidIdShortPathException(idShortPath, "Terminal SubmodelElement is not a collection");
		}
	}

	@Override
	public void patchSubmodelElementByPath(String idShortPath, SubmodelElement element) {
		m_dirty = true;
	}

	@Override
	public void patchSubmodelElementValueByPath(String idShortPath, SubmodelElementValue element) {
		m_dirty = true;
	}

	@Override
	public void deleteSubmodelElementByPath(String idShortPath) {
		String[] pathSegs = idShortPath.split(".");
		String parentIdShortPath = FStream.of(pathSegs)
											.dropLast(1)
											.join('.');
		
		String lastPathSeg = pathSegs[pathSegs.length-1];
		SubmodelElement parent = traverse(m_submodel, parentIdShortPath);
		if ( parent instanceof SubmodelElementCollection smec ) {
			List<SubmodelElement> values = smec.getValue();
			
			int idx = Funcs.findFirstIndexed(values, sme -> sme.getIdShort().equals(lastPathSeg))
							.map(Indexed::index)
							.getOrNull();
			if ( idx >= 0 ) {
				values.remove(idx);
				m_dirty = true;
			}
			else {
				throw new InvalidIdShortPathException(idShortPath, "Invalid IdShortPath");
			}
		}
		else {
			throw new InvalidIdShortPathException(idShortPath, "Terminal SubmodelElement is not a collection");
		}
	}

	@Override
	public OperationResult invokeOperationSync(String idShortPath, List<OperationVariable> inputArguments,
			List<OperationVariable> inoutputArguments, Duration timeout) {
		throw new UnsupportedOperationException();
	}

	@Override
	public OperationHandle invokeOperationAsync(String idShortPath, List<OperationVariable> inputArguments,
			List<OperationVariable> inoutputArguments, Duration timeout) {
		throw new UnsupportedOperationException();
	}

	@Override
	public OperationResult getOperationAsyncResult(OperationHandle handleId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public BaseOperationResult getOperationAsyncStatus(OperationHandle handleId) {
		throw new UnsupportedOperationException();
	}
	
	private static SubmodelElement traverse(Submodel submodel, String idShortPath) {
		Preconditions.checkNotNull(submodel, "Submodel was null");
		Preconditions.checkNotNull(idShortPath, "idShortPath was null");
		
		String[] pathSegs =idShortPath.split(".");
		Preconditions.checkArgument(pathSegs.length > 0);
		
		SubmodelElement found = null;
		List<SubmodelElement> smeList = submodel.getSubmodelElements();
		for ( int cursor = 0; cursor < pathSegs.length; ++cursor ) {
			String key = pathSegs[cursor];
			found = FStream.from(smeList)
							.findFirst(sme -> key.equals(sme.getIdShort()))
							.getOrNull();
			if ( smeList == null ) {
				throw new ResourceNotFoundException("SubmodelElement", idShortPath);
			}

			if ( (cursor+1) < pathSegs.length ) {
				if ( smeList instanceof SubmodelElementCollection smec ) {
					smeList = smec.getValue();
				}
				else {
					throw new ResourceNotFoundException("SubmodelElement", idShortPath);
				}
			}
		}
		
		return found;
	}
}
