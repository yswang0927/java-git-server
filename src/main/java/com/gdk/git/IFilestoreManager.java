package com.gdk.git;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public interface IFilestoreManager {

	boolean isValidOid(String oid);
	
	FilestoreModel.Status addObject(String oid, long size, UserModel user, RepositoryModel repo);
	
	FilestoreModel getObject(String oid, UserModel user, RepositoryModel repo);
	
	FilestoreModel.Status uploadBlob(String oid, long size, UserModel user, RepositoryModel repo, InputStream streamIn);
	
	FilestoreModel.Status downloadBlob(String oid, UserModel user, RepositoryModel repo, OutputStream streamOut);
	
	List<FilestoreModel> getAllObjects(UserModel user);
	
	File getStorageFolder();
	
	File getStoragePath(String oid);
	
	long getMaxUploadSize();
	
	void clearFilestoreCache();
	
	long getFilestoreUsedByteCount();
	
	long getFilestoreAvailableByteCount();

}
