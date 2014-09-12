/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 *     http://duracloud.org/license/
 */
package org.duracloud.mill.manifest;

import java.util.Date;
import java.util.Iterator;

import org.duracloud.error.NotFoundException;
import org.duracloud.mill.db.model.ManifestItem;

/**
 * @author Daniel Bernstein
 *         Date: Sep 2, 2014
 */
public interface ManifestStore {

    /**
     * @param account
     * @param storeId
     * @param spaceId
     * @param contentId
     * @param contentChecksum
     * @param contentSize 
     * @param contentMimetype 
     * @param timeStamp 
     * @throws ManifestItemWriteException
     */
    void addUpdate(String account,
             String storeId,
             String spaceId,
             String contentId,
             String contentChecksum,
             String contentMimetype,
             String contentSize, 
             Date timeStamp) throws ManifestItemWriteException;

    /**
     * @param account
     * @param storeId
     * @param spaceId
     * @return
     */
    Iterator<ManifestItem> getItems(String account,
                                    String storeId,
                                    String spaceId);


    /**
     * 
     * @param account
     * @param storeId
     * @param spaceId
     * @param contentId
     * @return
     * @throws NotFoundException
     */
    ManifestItem getItem(String account,
                         String storeId,
                         String spaceId,
                         String contentId) throws NotFoundException;

    /**
     * @param account
     * @param storeId
     * @param spaceId
     * @param contentId
     * @param eventTimestamp
     * @throws ManifestItemWriteException 
     */
    void flagAsDeleted(String account,
                       String storeId,
                       String spaceId,
                       String contentId,
                       Date eventTimestamp) throws ManifestItemWriteException;
    
    /**
     * 
     * @param expiration
     * @return Count of items deleted.
     */
    Long purgeDeletedItemsBefore(Date expiration);

}
