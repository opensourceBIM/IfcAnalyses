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

import com.google.common.base.Charsets;
import com.google.common.collect.BiMap;

public class AnalysesService  extends AbstractAddExtendedDataService {

	
	private static final Logger LOGGER = LoggerFactory.getLogger(AnalysesService.class);
	private final String STANDARD_SET_PREFIX = "something";
	
	
	public AnalysesService() {
		super("TODO");
		// TODO Auto-generated constructor stub
	}
	
	@Override
	public void newRevision(RunningService runningService, BimServerClientInterface bimServerClientInterface, long poid,
			long roid, String userToken, long soid, SObjectType settings) throws Exception {
		// TODO Auto-generated method stub
		StringBuffer extendedData = new StringBuffer();  
		SProject project;
		project = bimServerClientInterface.getServiceInterface().getProjectByPoid(poid);
		
		IfcModelInterface model =  bimServerClientInterface.getModel(project, roid, true, true, true);
		
		BiMap<Long, IdEObject> allObjects =  model.getObjects();
		List<IfcProduct> totalObjectsList = model.getAllWithSubTypes(IfcProduct.class);
		
		/* 
		 * The total number of objects 
		 */
		long totalObjects =  allObjects.keySet().size();
		extendedData.append("Total amount of objects: " + totalObjects + "\n");
		LOGGER.debug("Total amount of objects: " + totalObjects);
		
		/* 
		 * The total number of IfcProduct 
		 */
		long totalIfcObjects = totalObjectsList.size();
		extendedData.append("Total amount of IfcProducts: " + totalIfcObjects + "\n");
		LOGGER.debug("Total amount of IfcProducts: " + totalIfcObjects);

		/*
		 * Number of geometric triangles per m3 
		 * Top 10 objects with most geometric triangles per m3 
		 */
		
		long totalM3 = 0;
		for (IfcSpace ifcSpace : model.getAll(IfcSpace.class)) {
			Double volume = IfcUtils.getIfcQuantityVolume(ifcSpace, "Net Volume");
			if (volume != null) {
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
		extendedData.append("Total number of triangles : " + triangles + "\n");
		extendedData.append("Total m3 : " + totalM3 + "\n");
		extendedData.append("Number of triangles per m3: " + triangles/totalM3 + "\n");
		LOGGER.debug("total number of triangles : " + triangles);
		LOGGER.debug("total m3 : " + totalM3);
		LOGGER.debug("number of triangles per m3: " + triangles/totalM3);
		extendedData.append("Top 10 object with most number of triangles:\n");
		LOGGER.debug("Top 10 object with most number of triangles:\n");
		
		int nrOfTops = 0 ; 
		for (Long key : topMap.keySet())
		{
			if (nrOfTops >= 10)
				break;

			for (IfcProduct product: topMap.get(key))
			{
				extendedData.append("\t" + product.getName() + "(" + product.getGlobalId() + "): " + key.longValue() +  " triangles \n");
				LOGGER.debug("\t" + product.getName() + "(" + product.getGlobalId() + "): " + key.longValue() +  " triangles");
				nrOfTops++;
			}
		}
		
		/*
		 * Number of properties/property sets per object
		 * Objects with "Voodoo properysets" (property sets that do not start with a standardized name)  TODO : what is a standardized name
		 *	Number of proxy objects
		 */
		
		long propsetCount = 0;
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
					IfcPropertySetDefinition propSetDef = (IfcPropertySetDefinition)((IfcRelDefinesByProperties)def).getRelatingPropertyDefinition() ;
					if (propSetDef instanceof IfcPropertySet)
					{
						if (!propSetDef.getName().startsWith(STANDARD_SET_PREFIX) && !objectWithVodooSet.contains(ob))
							objectWithVodooSet.add(ob);
						propsetCount++;
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

		extendedData.append("Number of proxy objects: " + objProxyCount + "n");
		LOGGER.debug("Number of proxy objects: " + objProxyCount);

		extendedData.append("Number of properties: " + propCount + " in " + objWithPropCount + " objects \n");
		LOGGER.debug("Number of properties: " + propCount + " in " + objWithPropCount + " objects");
		extendedData.append("Number of properties: " + propCount + " in " + propsetCount + " propertysets \n");
		LOGGER.debug("Number of properties: " + propCount + " in " + propsetCount + " propertysets");
		extendedData.append("Number of IfcObject with voodoo propertySets: " + objectWithVodooSet.size() + "n");
		LOGGER.debug("Number of IfcObject with voodoo propertySets: " + objectWithVodooSet.size());
		
		for (IfcObject obj : objectWithVodooSet)
		{
			extendedData.append("\t" +  obj.getName() + "(" + obj.getGlobalId() + "\n");
			LOGGER.debug("\t" +  obj.getName() + "(" + obj.getGlobalId());
			
		}
		extendedData.append("Number of IfcObject with voodoo properties: " + objectWithVodooProp.size() + "n");
		LOGGER.debug("Number of IfcObject with voodoo properties: " + objectWithVodooProp.size());
		for (IfcObject obj : objectWithVodooProp)
		{
			extendedData.append("\t" +  obj.getName() + "(" + obj.getGlobalId() + "\n");
			LOGGER.debug("\t" +  obj.getName() + "(" + obj.getGlobalId());
			
		}
		
		
		/*
		 * Number of objects with Classification attributes
		 * Kind of classifications <TODO : find you what kind means> 
		 */
		
		ArrayList<Long> classifiedObjectsList = new ArrayList<Long>();
		List<IfcRelAssociatesClassification> classificationsList = model.getAllWithSubTypes(IfcRelAssociatesClassification.class);
		List<String> classificationKinds = new ArrayList<String>();
		
		for (IfcRelAssociatesClassification ifcRelAssociatesClassification : classificationsList)
		{
			if (!classificationKinds.contains(ifcRelAssociatesClassification.getName()))
				classificationKinds.add(ifcRelAssociatesClassification.getName());
			
			EList<IfcRoot> a = ifcRelAssociatesClassification.getRelatedObjects();
			for (IfcRoot ifcRoot : a)
			{
				if (!classifiedObjectsList.contains(ifcRoot.getOid()))
				{
					classifiedObjectsList.add(ifcRoot.getOid());
			    }
			}  
		}
		extendedData.append("Number of objects with classification: " + classifiedObjectsList.size() + "\n");
		LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());
		extendedData.append("Type of classifications: " + "\n");
		LOGGER.debug("Number of objects with classification: " + classifiedObjectsList.size());

		for (String classificationName : classificationKinds)
		{
			extendedData.append("\t"+ classificationName + "\n");
			LOGGER.debug("\t"+ classificationName);
			
		}
		
		addExtendedData(extendedData.toString().getBytes(Charsets.UTF_8), "test.txt", "Test", "text/plain", bimServerClientInterface, roid);
		
		// TODO : what will be the ouput format , now it;s plain text 
		
	}

}
