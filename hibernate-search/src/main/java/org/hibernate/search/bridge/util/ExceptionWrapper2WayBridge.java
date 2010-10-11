package org.hibernate.search.bridge.util;

import org.apache.lucene.document.Document;
import org.hibernate.search.bridge.TwoWayFieldBridge;

/**
 * Wrap the exception with an exception provide contextual feedback
 *
 * @author Emmanuel Bernard
 */
public class ExceptionWrapper2WayBridge extends ExceptionWrapperBridge implements TwoWayFieldBridge {
	private TwoWayFieldBridge delegate;

	public ExceptionWrapper2WayBridge setDelegate(TwoWayFieldBridge delegate) {
		super.setFieldBridge(delegate);
		this.delegate = delegate;
		return this;
	}

	public ExceptionWrapper2WayBridge setClassAndMethod(Class<?> clazz, String path) {
		super.setClassAndMethod(clazz, path);
		return this;
	}

	public ExceptionWrapper2WayBridge setFieldName(String fieldName) {
		super.setFieldName(fieldName);
		return this;
	}

	public Object get(String name, Document document) {
		try {
			return delegate.get(name, document);
		}
		catch (Exception e) {
			throw buildBridgeException(e, "get");
		}
	}

	public String objectToString(Object object) {
		try {
			return delegate.objectToString(object);
		}
		catch (Exception e) {
			throw buildBridgeException(e, "objectToString");
		}
	}
}
