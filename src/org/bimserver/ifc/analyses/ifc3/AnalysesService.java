package org.bimserver.ifc.analyses.ifc3;

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
		super("analysesNameSpace");
	}
	
	public void setOuputFormat(outputFormats outputformat)
	{
		this.outputFormat = outputformat;
	}
	
	@Override
	public void newRevision(RunningService runningService, BimServerClientInterface bimServerClientInterface, long poid,
			long roid, String userToken, long soid, SObjectType settings) throws Exception {
		
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
			ObjectNode totalIfcObjectsJSON = OBJECT_MAPPER.createObjectNode();
			totalIfcObjectsJSON.put("IfcObjects", totalIfcObjects);
			results.add(totalIfcObjectsJSON);
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
				// something went wrong, but don't kill the analyses for it
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
			trianglesPerM3JSON.put("triangles_per_m3", (totalM3>0?(triangles/totalM3):0));
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

		LOGGER.debug("Done checking objects for voodoo properties.");
		
		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalproxyJSON = OBJECT_MAPPER.createObjectNode();
			totalproxyJSON.put("Number of proxy objects", objProxyCount);
			results.add(totalproxyJSON);

			ObjectNode  totalpropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalpropertiesJSON.put("Number of properties", propCount);
			results.add(totalpropertiesJSON);

			ObjectNode  totalObjectsxWithPropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithPropertiesJSON.put("Ojects with properties", objWithPropCount);
			results.add(totalObjectsxWithPropertiesJSON);

			ObjectNode  totalObjectsxWithVoodooSetsJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithVoodooSetsJSON.put("Ojects with voodoo properties", objectWithVodooSet.size());
			results.add(totalObjectsxWithVoodooSetsJSON);

			ObjectNode  totalObjectsxWithVoodooPropertiesJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsxWithVoodooPropertiesJSON.put("Ojects with voodoo properties", objectWithVodooProp.size());	
			results.add(totalObjectsxWithVoodooPropertiesJSON);
		}
		else
		{
			extendedData.append("Number of proxy objects: " + objProxyCount + "n");
			LOGGER.debug("Number of proxy objects: " + objProxyCount);

			extendedData.append("Number of properties: " + propCount + " in " + objWithPropCount + " objects \n");
			LOGGER.debug("Number of properties: " + propCount + " in " + objWithPropCount + " objects");
			
			extendedData.append("Number of IfcObject with voodoo propertySets (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooSet.size() + "n");
			LOGGER.debug("Number of IfcObject with voodoo propertySets (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooSet.size());	
			
			extendedData.append("IfcObject with voodoo properties (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooProp.size() + "n");
			LOGGER.debug("IfcObject with voodoo properties (does not start with " + STANDARD_SET_PREFIX + "): " + objectWithVodooProp.size());

		}
		
		/*
		 * Number of objects with Classification attributes
		 */
		
		ArrayList<IfcRoot> classifiedObjectsList = new ArrayList<IfcRoot>();
		
		List<IfcRelAssociatesClassification> classificationsList = model.getAllWithSubTypes(IfcRelAssociatesClassification.class);
		
		for (IfcRelAssociatesClassification ifcRelAssociatesClassification : classificationsList)
		{
			EList<IfcRoot> a = ifcRelAssociatesClassification.getRelatedObjects();
			for (IfcRoot ifcRoot : a)
			{
				if (!classifiedObjectsList.contains(ifcRoot))
				{
					classifiedObjectsList.add(ifcRoot);
			    }
			}  
		}
		

		if (outputFormat == outputFormats.JSON)
		{
			ObjectNode  totalClassifications = OBJECT_MAPPER.createObjectNode();
			totalClassifications.put("Number of classification", classificationsList.size());
			results.add(totalClassifications);

			//log by Object
			ObjectNode  totalObjectsWithClassificationJSON = OBJECT_MAPPER.createObjectNode();
			totalObjectsWithClassificationJSON.put("Number of objects with classification", classifiedObjectsList.size());
			results.add(totalObjectsWithClassificationJSON);
			
		
		}
		else
		{
			// log by Objects
			
			extendedData.append("Number of objects with classification: " + classifiedObjectsList.size() + "\n");
			LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());
		}		
		
		if (outputFormat == outputFormats.JSON)
		{
			result.putPOJO("results", results);
			String json = result.toString();
			LOGGER.debug("Adding text to extended data : " + json);	
			addExtendedData(json.getBytes(Charsets.UTF_8), "test.txt", "Test", "text/plain", bimServerClientInterface, roid);

		}
		else
		{	
			LOGGER.debug("Adding text to extended data : " + extendedData.toString());	
			addExtendedData(extendedData.toString().getBytes(Charsets.UTF_8), "test.txt", "Test", "text/plain", bimServerClientInterface, roid);
		}
		
		
	}

}
