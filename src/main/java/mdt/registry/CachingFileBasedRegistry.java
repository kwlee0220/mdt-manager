package mdt.registry;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;

import utils.InternalException;
import utils.Throwables;
import utils.fostore.CachingFileObjectStore;
import utils.fostore.DefaultFileObjectStore;
import utils.fostore.FileObjectHandler;
import utils.fostore.FileObjectStore;
import utils.stream.FStream;

import mdt.model.AASUtils;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.ResourceNotFoundException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class CachingFileBasedRegistry<D> {
	private final String m_resourceName;
	private final CachingFileObjectStore<String, LazyDescriptor<D>> m_store;
	private final Method m_getIdShort;
	private final int m_cacheSize;
	
	public CachingFileBasedRegistry(File storeDir, int cacheSize, Class<D> descCls, Function<String,D> deser) {
		try {
			m_resourceName = descCls.getSimpleName();
			
			m_cacheSize = cacheSize;
			DescriptorHandler<D> descHandler = new DescriptorHandler<>(storeDir, deser);
			FileObjectStore<String,LazyDescriptor<D>> baseStore = new DefaultFileObjectStore<>(storeDir, descHandler);
			LoadingCache<String, LazyDescriptor<D>> cache = CacheBuilder.newBuilder()
														.maximumSize(cacheSize)
														.build(new DescriptorCacheLoader<>(baseStore));
			m_store = new CachingFileObjectStore<>(baseStore, cache);
			if ( !storeDir.exists() ) {
				Files.createDirectories(storeDir.toPath());
			}
			
			Method method;
			try {
				method = descCls.getDeclaredMethod("getIdShort");
			}
			catch ( Exception expected ) {
				method = null;
			}
			m_getIdShort = method;
		}
		catch ( IOException e ) {
			throw new InternalException("" + e);
		}
	}
	
	public File getStoreDir() {
		return m_store.getRootDir();
	}

	public LazyDescriptor<D> getDescriptorById(String id) throws ResourceNotFoundException {
		Preconditions.checkNotNull(id, m_resourceName + " id");
		
		try {
			Optional<LazyDescriptor<D>> resource = m_store.get(id);
			if ( resource.isEmpty() ) {
				throw new ResourceNotFoundException(m_resourceName, id);
			}
			
			return resource.get();
		}
		catch ( IOException e ) {
			throw new InternalException("" + e);
		}
		catch ( ExecutionException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

    public List<String> getAllDescriptorIds() {
    	try {
        	return Lists.newArrayList(m_store.getFileObjectKeyAll());
		}
		catch ( IOException e ) {
			throw new InternalException("" + e);
		}
    }

	public List<LazyDescriptor<D>> getAllDescriptors() {
		try {
			return m_store.getFileObjectAll();
		}
		catch ( IOException e ) {
			throw new InternalException("" + e);
		}
		catch ( ExecutionException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	public List<LazyDescriptor<D>> getAllDescriptorsByShortId(String idShort) {
		Preconditions.checkNotNull(idShort, m_resourceName + " idShort");
		
		try {
			FStream<LazyDescriptor<D>> stream = FStream.from(m_store.getFileObjectAll());
			if ( idShort != null ) {
				stream = stream.filter(desc -> filterByShortId(desc, idShort));
			}
			return stream.toList();
		}
		catch ( IOException e ) {
			throw new InternalException("" + e);
		}
		catch ( ExecutionException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	public LazyDescriptor<D> addDescriptor(String id, LazyDescriptor<D> descriptor)
		throws ResourceAlreadyExistsException {
		try {
			Preconditions.checkNotNull(descriptor);
			Preconditions.checkNotNull(id, m_resourceName + " id");
			
			Optional<File> file = m_store.insert(id, descriptor);
			if ( file.isEmpty() ) {
				throw new ResourceAlreadyExistsException(m_resourceName, id);
			}
			return descriptor;
		}
		catch ( IOException e ) {
			throw new InternalException("" + e);
		}
		catch ( ExecutionException e ) {
			Throwable cause = Throwables.unwrapThrowable(e);
			throw new InternalException("" + cause);
		}
	}

	public void removeDescriptor(String id) throws ResourceNotFoundException {
		Preconditions.checkNotNull(id, m_resourceName + " id");
		
    	try {
    		boolean done = m_store.remove(id);
    		if ( !done ) {
				throw new ResourceAlreadyExistsException(m_resourceName, id);
    		}
		}
		catch ( IOException e ) {
			throw new InternalException("" + e);
		}
	}

	public LazyDescriptor<D> updateDescriptor(String id, LazyDescriptor<D> descriptor)
		throws ResourceNotFoundException {
		removeDescriptor(id);
		return addDescriptor(id, descriptor);
	}
	
	@Override
	public String toString() {
		return String.format("resource=%s, store=%s, cache_size=%d",
								m_resourceName, m_store.getRootDir(), m_cacheSize);
	}
	
	private static final class DescriptorHandler<D> implements FileObjectHandler<String, LazyDescriptor<D>> {
		private final File m_rootDir;
		private final Function<String,D> m_deser;
		
		DescriptorHandler(File rootDir, Function<String,D> deser) {
			m_rootDir = rootDir;
			m_deser = deser;
		}
		
		@Override
		public LazyDescriptor<D> readFileObject(File file) throws IOException, ExecutionException {
			String jsonDesc = Files.readString(file.toPath());
			return new LazyDescriptor<>(jsonDesc, m_deser);
		}

		@Override
		public void writeFileObject(LazyDescriptor<D> obj, File file) throws IOException, ExecutionException {
			Files.writeString(file.toPath(), obj.getJson());
		}

		@Override
		public File toFile(String key) {;
			String encodedName = AASUtils.encodeBase64UrlSafe(key);
			return new File(m_rootDir, encodedName);
		}

		@Override
		public String toFileObjectKey(File file) {
			return AASUtils.decodeBase64UrlSafe(file.getName());
		}

		@Override
		public boolean isVallidFile(File file) {
			return file.getParentFile().equals(m_rootDir);
		}
	}
    
    private static class DescriptorCacheLoader<D> extends CacheLoader<String, LazyDescriptor<D>> {
    	private final FileObjectStore<String,LazyDescriptor<D>> m_store;
    	
    	DescriptorCacheLoader(FileObjectStore<String,LazyDescriptor<D>> store) {
    		m_store = store;
    	}
    	
		@Override
		public LazyDescriptor<D> load(String key) throws Exception {
			return m_store.get(key).get();
		}
    }
	
	private boolean filterByShortId(LazyDescriptor<D> desc, String idShort) {
		try {
			Object ret = m_getIdShort.invoke(desc.get());
			return idShort.equals(ret);
		}
		catch ( Exception e ) {
			return false;
		}
	}
}
