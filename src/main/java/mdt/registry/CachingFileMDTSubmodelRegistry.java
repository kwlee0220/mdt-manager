package mdt.registry;

import java.io.File;
import java.util.List;
import java.util.function.Function;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonSerializer;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;

import utils.InternalException;
import utils.stream.FStream;

import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class CachingFileMDTSubmodelRegistry implements SubmodelRegistryProvider {
    private final CachingFileBasedRegistry<SubmodelDescriptor> m_store;
    private final JsonSerializer m_jsonSer = new JsonSerializer();
	private final JsonDeserializer m_jsonDeser = new JsonDeserializer();
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public CachingFileMDTSubmodelRegistry(File storeDir, int cacheSize) {
    	m_store = new CachingFileBasedRegistry(storeDir, cacheSize, SubmodelDescriptor.class, m_deser);
    }
    
    public File getStoreDir() {
    	return m_store.getStoreDir();
    }

	@Override
	public SubmodelDescriptor getSubmodelDescriptorById(String submodelId) throws ResourceNotFoundException {
		return m_store.getDescriptorById(submodelId).get();
	}

	@Override
	public String getJsonSubmodelDescriptorById(String submodelId) throws ResourceNotFoundException {
		return m_store.getDescriptorById(submodelId).getJson();
	}

	@Override
	public List<SubmodelDescriptor> getAllSubmodelDescriptors() {
		return FStream.from(m_store.getAllDescriptors())
						.map(LazyDescriptor::get)
						.toList();
	}

	@Override
	public List<SubmodelDescriptor> getAllSubmodelDescriptorsByIdShort(String idShort) {
		return FStream.from(m_store.getAllDescriptorsByShortId(idShort))
						.map(LazyDescriptor::get)
						.toList();
	}

	@Override
	public List<SubmodelDescriptor> getAllSubmodelDescriptorsBySemanticId(String semanticId) {
		return FStream.from(m_store.getAllDescriptorsBySemanticId(semanticId))
						.map(LazyDescriptor::get)
						.toList();
	}

	@Override
	public SubmodelDescriptor postSubmodelDescriptor(SubmodelDescriptor descriptor)
		throws ResourceAlreadyExistsException {
		try {
			String json = m_jsonSer.write(descriptor);
			m_store.addDescriptor(descriptor.getId(), new LazyDescriptor<>(descriptor, json));
			return descriptor;
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to add SubmodelDescriptor: id=" + descriptor.getId()
										+ ", cause=" + e);
		}
	}

	@Override
	public SubmodelDescriptor putSubmodelDescriptorById(SubmodelDescriptor descriptor)
		throws ResourceNotFoundException {
		try {
			String json = m_jsonSer.write(descriptor);
			m_store.updateDescriptor(descriptor.getId(), new LazyDescriptor<>(descriptor, json));
			return descriptor;
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to update SubmodelDescriptor: id=" + descriptor.getId()
										+ ", cause=" + e);
		}
	}

	@Override
	public void deleteSubmodelDescriptorById(String submodelId) throws ResourceNotFoundException {
		m_store.removeDescriptor(submodelId);
	}
    
    private final Function<String,SubmodelDescriptor> m_deser = new Function<>() {
		@Override
		public SubmodelDescriptor apply(String json) {
			try {
				return m_jsonDeser.read(json, SubmodelDescriptor.class);
			}
			catch ( DeserializationException e ) {
				throw new InternalException("" + e);
			}
		}
    };
}
