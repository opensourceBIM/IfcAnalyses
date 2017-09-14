package org.bimserver.ifc.analyses;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bimserver.emf.IdEObject;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.interfaces.objects.SObjectType;
import org.bimserver.interfaces.objects.SProject;
import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcObject;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcProperty;
import org.bimserver.models.ifc2x3tc1.IfcPropertySet;
import org.bimserver.models.ifc2x3tc1.IfcPropertySetDefinition;
import org.bimserver.models.ifc2x3tc1.IfcPropertySingleValue;
import org.bimserver.models.ifc2x3tc1.IfcRelAssociatesClassification;
import org.bimserver.models.ifc2x3tc1.IfcRelDefines;
import org.bimserver.models.ifc2x3tc1.IfcRelDefinesByProperties;
import org.bimserver.models.ifc2x3tc1.IfcRoot;
import org.bimserver.models.ifc2x3tc1.IfcSpace;
import org.bimserver.models.ifc2x3tc1.IfcWallStandardCase;
import org.bimserver.models.ifc2x3tc1.Tristate;
import org.bimserver.models.ifc2x3tc1.impl.IfcObjectImpl;
import org.bimserver.plugins.services.AbstractAddExtendedDataService;
import org.bimserver.plugins.services.BimServerClientInterface;
import org.bimserver.utils.IfcUtils;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.collect.BiMap;


public class AnalysesService  extends AbstractAddExtendedDataService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private enum outputFormats {
		JSON,
		PLAIN;
	}
	
	private outputFormats outputFormat = outputFormats.JSON;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AnalysesService.class);
	private final String STANDARD_SET_PREFIX = "Pset_";
		
	public AnalysesService() {
		super("TODO");
	}
	
	public void setOuputFormat(outputFormats outputformat)
	{
		this.outputFormat = outputformat;
	}
	
	@Override
	public void newRevision(RunningService runningService, BimServerClientInterface bimServerClientInterface, long poid,
			long roid, String userToken, long soid, SObjectType settings) throws Exception {
		// TODO Auto-generated method stub
		
		
		LOGGER.debug("Starting Analayses !!!! ");			

		StringBuffer extendedData = new StringBuffer();  
		SProject project;
		project = bimServerClientInterface.getServiceInterface().getProjectByPoid(poid);
		
		IfcModelInterface model =  bimServerClientInterface.getModel(project, roid, true, true, true);
		
		
		ObjectNode result = OBJECT_MAPPER.createObjectNode();
		ArrayNode results = OBJECT_MAPPER.createArrayNode();
		
		BiMap<Long, IdEObject> allObjects =  model.getObjects();
		List<IfcProduct> totalObjectsList = model.getAllWithSubTypes(IfcProduct.class);
		
		
		/* 
		 * The total number of objects 
		 */
		long totalObjects =  allObjects.keySet().size();

		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalObjectsJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsJSON.put("objects", totalObjects);
			results.add(totalObjectsJSON);
		}
		else
		{
			extendedData.append("Total amount of objects: " + totalObjects + "\n");
			LOGGER.debug("Total amount of objects: " + totalObjects);			
		}
		
		
		
		/* 
		 * The total number of IfcProduct 
		 */
		long totalIfcObjects = totalObjectsList.size();
		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalIfcObjectsJSON = OBJECT_MAPPER.createObjectNode();
			totalIfcObjectsJSON.put("IfcObjects", totalIfcObjects);
			results.add(totalIfcObjects);
		}
		else
		{
			extendedData.append("Total amount of objects: " + totalObjects + "\n");
			LOGGER.debug("Total amount of objects: " + totalObjects);			
		}
		

		/*
		 * Number of geometric triangles per m3 
		 * Top 10 objects with most geometric triangles per m3 
		 */
		
		long totalM3 = 0;
		for (IfcSpace ifcSpace : model.getAll(IfcSpace.class)) {
			Double volume = new Double(0);
			try {
				volume = IfcUtils.getIfcQuantityVolume(ifcSpace, "NetVolume");
			} catch (Exception e) {
				// something went wrong, but don;t kill the analyses for it
				LOGGER.debug("Something went wrong for calculating the volume for: " + ifcSpace.getGlobalId());
				e.printStackTrace();
			}
			
			if (volume != null && volume.doubleValue() > 0) {
				totalM3 += volume;
			}
		}
		
		long triangles = 0;
		Map<String, AtomicInteger> map = new HashMap<>();

		TreeMap<Long, List<IfcProduct>> topMap = new TreeMap<>(new Comparator<Long>() {

	        @Override
	        public int compare(Long o1, Long o2) {
	            return o2>o1?1:o2==o1?0:-1;
	        }
	    });
		
		List<IfcProduct> allWithSubTypes = model.getAllWithSubTypes(IfcProduct.class);
		for (IfcProduct ifcProduct : allWithSubTypes) {
			if (!map.containsKey(ifcProduct.eClass().getName())) {
				map.put(ifcProduct.eClass().getName(), new AtomicInteger(0));
			}
			if (!(ifcProduct instanceof IfcWallStandardCase)) {
				continue;
			}
			
			
			Tristate isExternal = IfcUtils.getBooleanProperty(ifcProduct, "IsExternal");
			if (isExternal == Tristate.TRUE) {
				GeometryInfo geometryInfo = ifcProduct.getGeometry();
				if (geometryInfo != null) {
					GeometryData geometryData = geometryInfo.getData();
					if (geometryData != null) {
						map.get(ifcProduct.eClass().getName()).incrementAndGet();
						
						ByteBuffer indicesBuffer = ByteBuffer.wrap(geometryData.getIndices());
						indicesBuffer.order(ByteOrder.LITTLE_ENDIAN);
						IntBuffer indices = indicesBuffer.asIntBuffer();

						ByteBuffer verticesBuffer = ByteBuffer.wrap(geometryData.getVertices());
						verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);

						long currentTriangles = (indices.capacity() / 3);
						triangles += currentTriangles;
						if (!topMap.keySet().contains(new Long(triangles)))
						{
							topMap.put(new Long(triangles), new ArrayList<IfcProduct>());
						}
						topMap.get(new Long(triangles)).add(ifcProduct);
					}
				}
			}
		}
		
		
		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totaltrianglesJSON = OBJECT_MAPPER.createObjectNode();
			totaltrianglesJSON.put("triangles", triangles);
			results.add(totaltrianglesJSON);
			
			ObjectNode  M3JSON = OBJECT_MAPPER.createObjectNode();
			M3JSON.put("m3", totalM3);
			results.add(M3JSON);

			ObjectNode  trianglesPerM3JSON = OBJECT_MAPPER.createObjectNode();
			trianglesPerM3JSON.put("triangles_per_m3", triangles/totalM3);
			results.add(trianglesPerM3JSON);

			ObjectNode  top10ObjectJSON = OBJECT_MAPPER.createObjectNode();
			ArrayNode  top10JSON = OBJECT_MAPPER.createArrayNode();

			int nrOfTops = 0 ; 
			for (Long key : topMap.keySet())
			{
				if (nrOfTops >= 10)
					break;

				for (IfcProduct product: topMap.get(key))
				{
					top10ObjectJSON.put("top10trianglesOID", product.getGlobalId() );
					top10JSON.add(top10ObjectJSON);
					nrOfTops++;
				}
			}
			results.add(top10JSON);

		
		}
		else
		{
			extendedData.append("Total number of triangles : " + triangles + "\n");
			extendedData.append("Total m3 : " + totalM3 + "\n");
			extendedData.append("Number of triangles per m3: " + triangles/totalM3 + "\n");
			LOGGER.debug("total number of triangles : " + triangles);
			LOGGER.debug("total m3 : " + totalM3);
			LOGGER.debug("number of triangles per m3: " + triangles/totalM3);
			extendedData.append("Top 10 object with most number of triangles:\n");
			LOGGER.debug("Top 10 object with most number of triangles:\n");
		}
		
		
		
		long propCount = 0;
	    long objWithPropCount = 0 ;
	    long objProxyCount = 0 ;
	    List<IfcObject> objectWithVodooSet = new ArrayList<IfcObject>();
	    List<IfcObject> objectWithVodooProp = new ArrayList<IfcObject>();
	   
 		for (Long id  : allObjects.keySet())
		{
			IdEObject eObject = model.get(id);
			if (eObject.eIsProxy()) 
				objProxyCount++ ;
			
			if (eObject instanceof IfcObjectImpl && (eObject instanceof IfcPropertySet)==false && (eObject instanceof IfcPropertySingleValue)==false  )
			{
				
				IfcObject ob = (IfcObject)eObject;
				for (IfcRelDefines def : ob.getIsDefinedBy())
				{
					objWithPropCount++;
					if (def instanceof IfcRelDefinesByProperties)
					{
						IfcPropertySetDefinition propSetDef = (IfcPropertySetDefinition)((IfcRelDefinesByProperties)def).getRelatingPropertyDefinition() ;
						if (propSetDef instanceof IfcPropertySet)
						{
							if (!propSetDef.getName().startsWith(STANDARD_SET_PREFIX) && !objectWithVodooSet.contains(ob))
								objectWithVodooSet.add(ob);
							for (IfcProperty prop:((IfcPropertySet)propSetDef).getHasProperties())
							{
								if (!prop.getName().startsWith(STANDARD_SET_PREFIX) && !objectWithVodooProp.contains(ob))
									objectWithVodooProp.add(ob);
								if (prop instanceof IfcPropertySingleValue){
									propCount++;
								}
							}
						}
					}
				}
			}
		}

 		
		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalproxyJSON = OBJECT_MAPPER.createObjectNode();
			totalproxyJSON.put("Number of proxy objects", objProxyCount);
			results.add(totalproxyJSON);
			results.add(totalproxyJSON);

			ObjectNode  totalpropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalpropertiesJSON.put("Number of properties", propCount);
			results.add(totalpropertiesJSON);

			ObjectNode  totalObjectsxWithPropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithPropertiesJSON.put("Ojects with properties", objWithPropCount);
			results.add(totalObjectsxWithPropertiesJSON);
			
			ObjectNode  totalObjectsxWithVoodooPropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithVoodooPropertiesJSON.put("Ojects with voodoo properties", objectWithVodooSet.size());
			
			ObjectNode  objectWithVodooPropertiesSetJSON = OBJECT_MAPPER.createObjectNode();
			ArrayNode  objectWithVodooPropertiesSetArrayJSON = OBJECT_MAPPER.createArrayNode();
			
			for (IfcObject obj : objectWithVodooSet)
			{
				objectWithVodooPropertiesSetJSON.put("voodooPropertySet", obj.getName() + "(" + obj.getGlobalId() + ")");
				objectWithVodooPropertiesSetArrayJSON.add(objectWithVodooPropertiesSetJSON);
			}
			
			results.add(objectWithVodooPropertiesSetArrayJSON);
			
			ObjectNode  objectWithVodooPropertiesJSON = OBJECT_MAPPER.createObjectNode();
			ArrayNode  objectWithVodooPropertiesArrayJSON = OBJECT_MAPPER.createArrayNode();
			
			for (IfcObject obj : objectWithVodooSet)
			{
				objectWithVodooPropertiesJSON.put("voodooPropertySet", obj.getName() + "(" + obj.getGlobalId() + ")");
				objectWithVodooPropertiesArrayJSON.add(objectWithVodooPropertiesJSON);
			}
			
			results.add(objectWithVodooPropertiesArrayJSON);

		}
		else
		{
			extendedData.append("Number of proxy objects: " + objProxyCount + "n");
			LOGGER.debug("Number of proxy objects: " + objProxyCount);

			extendedData.append("Number of properties: " + propCount + " in " + objWithPropCount + " objects \n");
			LOGGER.debug("Number of properties: " + propCount + " in " + objWithPropCount + " objects");
			extendedData.append("Number of IfcObject with voodoo propertySets (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooSet.size() + "n");
			LOGGER.debug("Number of IfcObject with voodoo propertySets (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooSet.size());
			
			extendedData.append("IfcObject with voodoo sets: " + objectWithVodooProp.size() + "n");
			LOGGER.debug("IfcObject with voodoo sets: " + objectWithVodooProp.size());
			for (IfcObject obj : objectWithVodooSet)
			{
				extendedData.append("\t" +  obj.getName() + "(" + obj.getGlobalId() + "\n");
				LOGGER.debug("\t" +  obj.getName() + "(" + obj.getGlobalId());
				
			}
			extendedData.append("IfcObject with voodoo properties: " + objectWithVodooProp.size() + "n");
			LOGGER.debug("IfcObject with voodoo properties: " + objectWithVodooProp.size());
			for (IfcObject obj : objectWithVodooProp)
			{
				extendedData.append("\t" +  obj.getName() + "(" + obj.getGlobalId() + "\n");
				LOGGER.debug("\t" +  obj.getName() + "(" + obj.getGlobalId());
				
			}

		}
		
		

		
		
		/*
		 * Number of objects with Classification attributes
		 * Kind of classifications <TODO : find out what "kind" means> 
		 */
		
		ArrayList<Long> classifiedObjectsList = new ArrayList<Long>();
		List<IfcRelAssociatesClassification> classificationsList = model.getAllWithSubTypes(IfcRelAssociatesClassification.class);
		Map<String, List<String>> classificationByKinds = new HashMap<String, List<String>>();
		Map<String, List<String>> classificationByObject = new HashMap<String, List<String>>();
		
		for (IfcRelAssociatesClassification ifcRelAssociatesClassification : classificationsList)
		{
			if (!classificationByKinds.containsKey(ifcRelAssociatesClassification.getName()))
				classificationByKinds.put(ifcRelAssociatesClassification.getName(),new ArrayList<>());
			EList<IfcRoot> a = ifcRelAssociatesClassification.getRelatedObjects();
			for (IfcRoot ifcRoot : a)
			{
				if (!classificationByObject.containsKey(ifcRoot.getGlobalId()))
				{
					classificationByObject.put(ifcRoot.getGlobalId(), new ArrayList<String>());
				}
				classificationByObject.get(ifcRoot.getGlobalId()).add(ifcRelAssociatesClassification.getName());
				classificationByKinds.get(ifcRelAssociatesClassification.getName()).add(ifcRoot.getName() + "(" + ifcRoot.getGlobalId() + ")");
				if (!classifiedObjectsList.contains(ifcRoot.getOid()))
				{
					classifiedObjectsList.add(ifcRoot.getOid());
			    }
			}  
		}
		

		if (outputFormat == outputFormats.JSON)
		{
			//log by kinds
			ObjectNode  totalObjectsWithClassificationJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsWithClassificationJSON.put("Number of objects with classification", classifiedObjectsList.size());
			results.add(totalObjectsWithClassificationJSON);

			ObjectNode  classificationTypeJSON = OBJECT_MAPPER.createObjectNode();
			ObjectNode  objectWithClassificationTypeJSON = OBJECT_MAPPER.createObjectNode();
			ArrayNode  classificationTypeArrayJSON = OBJECT_MAPPER.createArrayNode();
			ArrayNode  objectsWithClassificationTypeArrayJSON = OBJECT_MAPPER.createArrayNode();
		
			for (String classificationName : classificationByKinds.keySet())
			{
				classificationTypeJSON.put("Classification", classificationName);
				
				for (String object : classificationByKinds.get(classificationName) )
				{	
					objectWithClassificationTypeJSON.put("IfcObject", object);
					objectsWithClassificationTypeArrayJSON.add(objectWithClassificationTypeJSON);
				}
				classificationTypeJSON.putPOJO("objects", objectsWithClassificationTypeArrayJSON);
				
				classificationTypeArrayJSON.add(classificationTypeJSON);
			}
			
			results.add(classificationTypeArrayJSON);
		}
		else
		{
			//log by kinds
			extendedData.append("Number of objects with classification: " + classifiedObjectsList.size() + "\n");
			LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());
			extendedData.append("Type of classifications: " + "\n");
			LOGGER.debug("Type of classifications:");
			for (String classificationName : classificationByKinds.keySet())
			{
				extendedData.append("\t"+ classificationName + ":\n");
				LOGGER.debug("\t"+ classificationName+ ":");	
				for (String object : classificationByKinds.get(classificationName) )
				{
					extendedData.append("\t\t"+ object + "\n");
					LOGGER.debug("\t\t"+ object);	

				}
			}
			
			// log by Objects

			
			extendedData.append("Number of objects with classification: " + classifiedObjectsList.size() + "\n");
			LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());
			extendedData.append("Objects:" + "\n");
			LOGGER.debug("Objects:");
			for (String objectId : classificationByObject.keySet())
			{
				extendedData.append("\t"+ objectId + ":\n");
				LOGGER.debug("\t"+ objectId+ ":");	
				for (String classification : classificationByObject.get(objectId) )
				{
					extendedData.append("\t\t"+ classification + "\n");
					LOGGER.debug("\t\t"+ classification );	

				}
			}

		}		
		
//		// log by Objects
//
//		
//		extendedData.append("Number of objects with classification: " + classifiedObjectsList.size() + "\n");
//		LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());
//		extendedData.append("Objects:" + "\n");
//		LOGGER.debug("Objects:");
//		for (String objectId : classificationByObject.keySet())
//		{
//			extendedData.append("\t"+ objectId + ":\n");
//			LOGGER.debug("\t"+ objectId+ ":");	
//			for (String classification : classificationByObject.get(objectId) )
//			{
//				extendedData.append("\t\t"+ classification + "\n");
//				LOGGER.debug("\t\t"+ classification );	
//
//			}
//		}

		
		if (outputFormat == outputFormats.JSON)
		{
			result.putPOJO("results", results);
			String json = result.toString();
			addExtendedData(json.getBytes(Charsets.UTF_8), "test.txt", "Test", "text/plain", bimServerClientInterface, roid);
			LOGGER.debug("Added text to extended data : " + json);	
		}
		else
		{	
			addExtendedData(extendedData.toString().getBytes(Charsets.UTF_8), "test.txt", "Test", "text/plain", bimServerClientInterface, roid);
		}
		
		// TODO : what will be the ouput format , now it;s plain text 
		
	}

}
