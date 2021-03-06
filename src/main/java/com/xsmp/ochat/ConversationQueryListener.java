/**
 * 
 */
package com.xsmp.ochat;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.servlet.http.HttpServletRequest;

import org.apache.olingo.odata2.api.edm.EdmException;
import org.apache.olingo.odata2.api.processor.ODataContext;
import org.apache.olingo.odata2.api.uri.UriInfo;
import org.apache.olingo.odata2.api.uri.info.GetEntityCountUriInfo;
import org.apache.olingo.odata2.api.uri.info.GetEntitySetCountUriInfo;
import org.apache.olingo.odata2.api.uri.info.GetEntitySetUriInfo;
import org.apache.olingo.odata2.api.uri.info.GetEntityUriInfo;
import org.apache.olingo.odata2.jpa.processor.api.ODataJPAQueryExtensionEntityListener;
import org.apache.olingo.odata2.jpa.processor.api.exception.ODataJPAModelException;
import org.apache.olingo.odata2.jpa.processor.api.exception.ODataJPARuntimeException;
import org.apache.olingo.odata2.jpa.processor.api.jpql.JPQLContext;
import org.apache.olingo.odata2.jpa.processor.api.jpql.JPQLContextType;
import org.apache.olingo.odata2.jpa.processor.api.jpql.JPQLStatement;
import org.apache.olingo.odata2.jpa.processor.core.ODataJPAContextImpl;
import org.apache.olingo.odata2.jpa.processor.core.access.data.JPAQueryBuilder.UriInfoType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xsmp.id.IdentityInteraction;
import com.xsmp.ochat.model.MessageUser;

/**
 * @author Riley Rainey <riley.rainey@sap.com>
 *
 */
public class ConversationQueryListener extends ODataJPAQueryExtensionEntityListener {
	
	private static Logger logger = LoggerFactory.getLogger(ConversationQueryListener.class);
	
	private int pageSize = 50;
	
	private Query buildQuery(UriInfo uriParserResultView, UriInfoType type, EntityManager em)
			throws EdmException,
			ODataJPAModelException, ODataJPARuntimeException {

		Query query = null;
		JPQLContextType contextType = determineJPQLContextType(uriParserResultView, type);
		JPQLContext jpqlContext = buildJPQLContext(contextType, uriParserResultView);
		JPQLStatement jpqlStatement = JPQLStatement.createBuilder(jpqlContext).build();
		
		/*
		 * Get the authenticated username
		 */
		ODataContext ctx = ODataJPAContextImpl.getContextInThreadLocal();
		HttpServletRequest r = (HttpServletRequest) ctx.getParameter(ODataContext.HTTP_SERVLET_REQUEST_OBJECT);
		String user = r.getRemoteUser();
		if (user != null) {
			MessageUser u = IdentityInteraction.verifyUser(r, em);
			logger.debug( "username from HttpServletRequest '"+u.getUsername()+"'" );
		}
		else {
			// This error will have the side effect of returning an empty result set
			logger.error("Assertion error: There is no authenticated user defined in the HttpServletRequest -- " +
					"check your web.xml application configuration");
		}
		
		String originalJpql = jpqlStatement.toString();
		
		/*
		 * Adjust the JPQL query to apply our context/security rule:
		 * 
		 * 1. A Conversation is only visible if the authenticated user is a member of the conversation.
		 * 
		 *   On order to do this, we need an approach to alter the JQPL generated by Olingo.  For example,
		 *   a /Conversations URI will translate to this JQPL (using Olingo 2.0.5)
		 *   
		 *   "SELECT E1 FROM Conversation E1 ORDER BY E1.conversationId"
		 *   
		 *   In that instance, we'd need to morph that into:
		 *   
		 *   "SELECT DISTINCT E1 from Conversation E1 JOIN E1.messageUsers E0 
		 *   	where E0.username = '<authenticated-username>' ORDER BY E1.converastionId"
		 * 
		 * So, here's how we do that:
		 * 
		 * Part 1: remove (and save) the ORDER BY clause if present
		 */
		
		String[] statementParts = originalJpql.split(JPQLStatement.KEYWORD.ORDERBY);
		
		/*
		 * Part 2: Add JOIN of memberUsers list and extra WHERE clause to filter down to
		 *         only those groups where the authenticated user is a member.
		 */
		String statement;
		String[] statementParts1 = statementParts[0].split(JPQLStatement.KEYWORD.WHERE);
        String userMembershipCondition = "E0.username = :username";
        String messageUsersMember = jpqlContext.getJPAEntityAlias() + ".messageUsers";

        if (statementParts1.length > 1) {
          statement =
              statementParts1[0] + JPQLStatement.DELIMITER.SPACE + JPQLStatement.KEYWORD.JOIN
              	  + JPQLStatement.DELIMITER.SPACE + messageUsersMember + " E0 " 
            	  + JPQLStatement.KEYWORD.WHERE
                  + JPQLStatement.DELIMITER.SPACE + userMembershipCondition + JPQLStatement.DELIMITER.SPACE
                  + JPQLStatement.Operator.AND + statementParts1[1];
        } 
        else {
          statement =
              statementParts1[0] + JPQLStatement.DELIMITER.SPACE + JPQLStatement.KEYWORD.JOIN
          	  + JPQLStatement.DELIMITER.SPACE + messageUsersMember + " E0 "
        	  + JPQLStatement.KEYWORD.WHERE
                  + JPQLStatement.DELIMITER.SPACE + userMembershipCondition;
        }
        
        /*
         * Part 3: Change SELECT to SELECT DISTINCT
         */
        
		String s1 = statement.replaceFirst("SELECT", "SELECT DISTINCT");
		/*
		 * Part 4: restore ORDER BY clause if it was originally present
		 */
		if (statementParts.length > 1) {
			s1 = s1 + JPQLStatement.DELIMITER.SPACE + JPQLStatement.KEYWORD.ORDERBY 
					+ JPQLStatement.DELIMITER.SPACE + statementParts[1];
		}
		
		logger.debug( "generated query \""+s1+"\", user " + user );
		query = em.createQuery( s1 );
		query.setParameter("username", user);

		return query;
	}

	private JPQLContext buildJPQLContext(JPQLContextType contextType, UriInfo uriParserResultView)
			throws ODataJPAModelException, ODataJPARuntimeException {
		JPQLContext jpqlContext = null;
		if (pageSize > 0 && (contextType == JPQLContextType.SELECT || contextType == JPQLContextType.JOIN)) {
			jpqlContext = JPQLContext.createBuilder(contextType, uriParserResultView, true).build();
		} else {
			jpqlContext = JPQLContext.createBuilder(contextType, uriParserResultView).build();
		}
		return jpqlContext;
	}

	private JPQLContextType determineJPQLContextType(UriInfo uriParserResultView, UriInfoType type) {
		JPQLContextType contextType = null;

		if (uriParserResultView.getNavigationSegments().size() > 0) {
			if (type == UriInfoType.GetEntitySet) {
				contextType = JPQLContextType.JOIN;
			} else if (type == UriInfoType.Delete || type == UriInfoType.Delete || type == UriInfoType.GetEntity
					|| type == UriInfoType.PutMergePatch) {
				contextType = JPQLContextType.JOIN_SINGLE;
			} else if (type == UriInfoType.GetEntitySetCount || type == UriInfoType.GetEntityCount) {
				contextType = JPQLContextType.JOIN_COUNT;
			}
		} else {
			if (type == UriInfoType.GetEntitySet) {
				contextType = JPQLContextType.SELECT;
			} else if (type == UriInfoType.Delete || type == UriInfoType.GetEntity
					|| type == UriInfoType.PutMergePatch) {
				contextType = JPQLContextType.SELECT_SINGLE;
			} else if (type == UriInfoType.GetEntitySetCount || type == UriInfoType.GetEntityCount) {
				contextType = JPQLContextType.SELECT_COUNT;
			}
		}
		return contextType;
	}

	@Override
	public Query getQuery(GetEntityUriInfo uriInfo, EntityManager em) {
		
		Query query = null;
		try {
			query = buildQuery((UriInfo) uriInfo, UriInfoType.GetEntity, em);
		} catch (Exception e) {
			logger.error("Exception while building query in getQuery(); no query could be generated");
			query = null;
		}
		logger.info("GetEntity query: " + query.toString());
		return query;
	}

	@Override
	public Query getQuery(GetEntitySetUriInfo uriInfo, EntityManager em) {
		Query query = null;
		try {
			query = buildQuery((UriInfo) uriInfo, UriInfoType.GetEntitySet, em);
		} catch (Exception e) {
			logger.error("Exception while building query in getQuery(); no query could be generated");
			query = null;
		}
		logger.info("GetEntitySet query: " + query.toString());
		return query;
	}
	
	@Override
    public Query getQuery(GetEntitySetCountUriInfo uriInfo, EntityManager em) {
		 Query query = null;
		try {
			query = buildQuery((UriInfo) uriInfo, UriInfoType.GetEntitySetCount, em);
		} catch (Exception e) {
			logger.error("Exception while building query in getQuery(); no query could be generated");
			query = null;
		}
		logger.info("GetEntitySetCount query: " + query.toString());
		return query;
    }
	
	@Override
    public Query getQuery(GetEntityCountUriInfo uriInfo, EntityManager em) {
    	Query query = null;
		try {
			query = buildQuery((UriInfo) uriInfo, UriInfoType.GetEntityCount, em);
		} catch (Exception e) {
			logger.error("Exception while building query in getQuery(); no query could be generated");
			query = null;
		}
		logger.info("GetEntityCount query: " + query.toString());
		return query;
    }

	@Override
	public boolean isTombstoneSupported() {
		return false;
	}

	/*
    @Override
    public Query getQuery(DeleteUriInfo uriInfo, EntityManager em) {
      return query;
    }

    @Override
    public Query getQuery(PutMergePatchUriInfo uriInfo, EntityManager em) {
      return query;
    }
    */

}
