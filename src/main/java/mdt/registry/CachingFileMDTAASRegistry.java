package mdt.registry;

import java.io.File;
import java.util.List;
import java.util.function.Function;

import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.DeserializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.core.SerializationException;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonDeserializer;
import org.eclipse.digitaltwin.aas4j.v3.dataformat.json.JsonSerializer;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;

import utils.InternalException;
import utils.stream.FStream;

import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class CachingFileMDTAASRegistry implements AASRegistryProvider {
    private final CachingFileBasedRegistry<AssetAdministrationShellDescriptor> m_store;
    private final JsonSerializer m_jsonSer = new JsonSerializer();
	private final JsonDeserializer m_jsonDeser = new JsonDeserializer();

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public CachingFileMDTAASRegistry(File storeDir, int cacheSize) {
    	m_store = new CachingFileBasedRegistry(storeDir, cacheSize,
    											AssetAdministrationShellDescriptor.class, m_deser);
    }
    
    public File getStoreDir() {
    	return m_store.getStoreDir();
    }

	@Override
	public List<AssetAdministrationShellDescriptor> getAllAssetAdministrationShellDescriptors() {
		return FStream.from(m_store.getAllDescriptors())
						.map(LazyDescriptor::get)
						.toList();
	}

	@Override
	public AssetAdministrationShellDescriptor getAssetAdministrationShellDescriptorById(String shellId)
														throws ResourceNotFoundException {
		return m_store.getDescriptorById(shellId).get();
	}

	@Override
	public List<AssetAdministrationShellDescriptor> getAllAssetAdministrationShellDescriptorByAssetId(String assetId)
														throws ResourceNotFoundException {
		return FStream.from(m_store.getAllDescriptors())
						.map(LazyDescriptor::get)
						.filter(desc -> assetId.equals(desc.getGlobalAssetId()))
						.toList();
	}

	@Override
	public String getJsonAssetAdministrationShellDescriptorById(String aasId) throws ResourceNotFoundException {
		return m_store.getDescriptorById(aasId).getJson();
	}

	@Override
	public List<AssetAdministrationShellDescriptor>
	getAllAssetAdministrationShellDescriptorsByIdShort(String idShort) {
		return FStream.from(m_store.getAllDescriptorsByShortId(idShort))
						.map(LazyDescriptor::get)
						.toList();
	}

	@Override
	public AssetAdministrationShellDescriptor
	addAssetAdministrationShellDescriptor(AssetAdministrationShellDescriptor descriptor)
		throws ResourceAlreadyExistsException {
		try {
			String json = m_jsonSer.write(descriptor);
			m_store.addDescriptor(descriptor.getId(), new LazyDescriptor<>(descriptor, json));
			return descriptor;
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to add AssetAdministrationShellDescriptor: id=" + descriptor.getId()
										+ ", cause=" + e);
		}
	}

	@Override
	public AssetAdministrationShellDescriptor
	updateAssetAdministrationShellDescriptor(AssetAdministrationShellDescriptor descriptor)
		throws ResourceNotFoundException {
		try {
			String json = m_jsonSer.write(descriptor);
			m_store.updateDescriptor(descriptor.getId(), new LazyDescriptor<>(descriptor, json));
			return descriptor;
		}
		catch ( SerializationException e ) {
			throw new InternalException("Failed to update AssetAdministrationShellDescriptor: id=" + descriptor.getId()
										+ ", cause=" + e);
		}
	}

	@Override
	public void removeAssetAdministrationShellDescriptorById(String aasId)
		throws ResourceNotFoundException {
		m_store.removeDescriptor(aasId);
	}
    
    private final Function<String,AssetAdministrationShellDescriptor> m_deser = new Function<>() {
		@Override
		public AssetAdministrationShellDescriptor apply(String json) {
			try {
				return m_jsonDeser.read(json, AssetAdministrationShellDescriptor.class);
			}
			catch ( DeserializationException e ) {
				throw new InternalException("" + e);
			}
		}
    };
}
