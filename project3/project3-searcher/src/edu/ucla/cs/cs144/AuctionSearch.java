package edu.ucla.cs.cs144;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.File;
import java.util.Date;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.lucene.document.Document;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import edu.ucla.cs.cs144.DbManager;
import edu.ucla.cs.cs144.SearchRegion;
import edu.ucla.cs.cs144.SearchResult;

public class AuctionSearch implements IAuctionSearch {

	/* 
         * You will probably have to use JDBC to access MySQL data
         * Lucene IndexSearcher class to lookup Lucene index.
         * Read the corresponding tutorial to learn about how to use these.
         *
	 * You may create helper functions or classes to simplify writing these
	 * methods. Make sure that your helper functions are not public,
         * so that they are not exposed to outside of this class.
         *
         * Any new classes that you create should be part of
         * edu.ucla.cs.cs144 package and their source files should be
         * placed at src/edu/ucla/cs/cs144.
         *
         */
	
	private IndexSearcher searcher = null;
    private QueryParser parser = null;
	
    public AuctionSearch() {
		try {
			searcher = new IndexSearcher(DirectoryReader.open(FSDirectory.open(new File("/var/lib/lucene/index1"))));
			parser = new QueryParser("content", new StandardAnalyzer());
		} catch (IOException ex) {
			System.out.println(ex);
		}
    }
	
	public SearchResult[] basicSearch(String query, int numResultsToSkip, 
			int numResultsToReturn) {
		
		try {
			// parse the query
			Query queryObj = parser.parse(query);
			
			// obtain document results from query using index searcher; find all documents up to the last requested one
			TopDocs results = searcher.search(queryObj, numResultsToSkip + numResultsToReturn);
			
			// use TopDocs to find total hits found and the array of ScoreDoc objects
			int totalHits = results.totalHits;
			ScoreDoc[] docs = results.scoreDocs;
			
			// if results to return tries to reach beyond total hits available, set a cap
			// numResultsToReturn should be either the remaining available documents above numResultsToSkip
			// or in the case where numResultsToSkip exceeds totalHits, it should return nothing
			if(totalHits < (numResultsToSkip + numResultsToReturn))
				numResultsToReturn = Math.max(0, totalHits - numResultsToSkip);
			
			// initialize SearchResult array to have expected number of results
			SearchResult[] searchResults = new SearchResult[numResultsToReturn];
			
			// populate the array with corresponding SearchResult objects
			for(int i=0; i<searchResults.length; i++) {
				Document temp = getDocument(docs[numResultsToSkip + i].doc);
				searchResults[i] = new SearchResult(temp.get("ItemID"), temp.get("Name"));
			}
			
			// return results
			return searchResults;
			
		} catch (ParseException ex) {
			System.out.println(ex);
		} catch (IOException ex) {
			System.out.println(ex);
		}
		
		// otherwise, return no results
		return new SearchResult[0];
	}
	
	public SearchResult[] spatialSearch(String query, SearchRegion region,
			int numResultsToSkip, int numResultsToReturn) {
		
		//SELECT ItemID, MBRContains(GeomFromText('Polygon((33.774 -118.63, 33.774 -117.38, 34.201 -117.38, 34.201 -118.63, 33.774 -118.63))'), Position) AS isContained FROM Locations WHERE ItemID IN (SELECT ItemID FROM Items WHERE Description LIKE '%camera%' AND ItemID<1496912345);

		int index = 0; // tracks search result returned from first running basic search
		int skipped = 0; // tracks number of basic search results in region that were skipped
		int added = 0; // tracks number of basic search results in region that were added to array list
		
		ArrayList<SearchResult> resultsList = new ArrayList<SearchResult>();
		
		// keep fetching results that satisfy the basic query search
		SearchResult[] temp = basicSearch(query, index, numResultsToReturn);

		// establish a connection with database
		Connection conn = null;
		
		try {
			// start database connection
			conn = DbManager.getConnection(true);
			
			// create the string for a MySQL geometric polygon for parameter region
			String polygon = getPolygon(region.getLx(), region.getLy(), region.getRx(), region.getRy());
			
			// prepared statement to test a specific item (by ItemID) for spatial containment in polygon region
			PreparedStatement checkContains = conn.prepareStatement("SELECT MBRContains(" + polygon + ",Position) AS isContained FROM Locations WHERE ItemID = ?");
			
			// as long as we haven't added the numResultsToReturn and basic search still returns results
			while(added < numResultsToReturn && temp.length > 0) {

				//System.out.println("index/temp.length/added/skipped: " + index + "/" + temp.length + "/" + added + "/" + skipped);
				
				for(int i=0; i<temp.length; i++) {
					
					// get SearchResult's ItemId, plug it into prepared statement, and check if the item is spatially found in region
					String itemId = temp[i].getItemId();
					checkContains.setString(1, itemId);
					ResultSet containsRS = checkContains.executeQuery();
										
					// if specific item is found in Locations table and is contained in region
					if(containsRS.next() && containsRS.getBoolean("isContained")) {
						if(added >= numResultsToReturn) {
							// enough results have been added to array list, so break out of loop
							break;
						}
						if(skipped >= numResultsToSkip) {
							// we've skipped enough results, so add SearchResult to array list
							added++;
							resultsList.add(temp[i]);
						}
						else {
							// skip and don't add to array list, but increment the skipped count
							skipped++;
						}
					}
					
					// close containsRS
					containsRS.close();
				}
				
				// lookup the next numResultsToReturn amount of basic search results to continue comparing
				index += numResultsToReturn;
				temp = basicSearch(query, index, numResultsToReturn);
			}
			
			// close statements and database connection
			checkContains.close();
			conn.close();
			
		} catch (SQLException ex) {
			System.out.println(ex);
		}
		
		// move results from dynamic array list over to array
		// SearchResult[] searchResults = resultsList.toArray(new SearchResult[added]);
		SearchResult[] searchResults = new SearchResult[added];
		for(int i=0; i<added; i++) {
			searchResults[i] = resultsList.get(i);
		}
		
		return searchResults;
	}

	public String getXMLDataForItemId(String itemId) {
		
		/*
		// SAMPLE MEDIOCRE XML REFERENCE:
		
		<Item ItemID="1043374545">
			<Name>christopher radko | fritz n_ frosty sledding</Name>
			<Category>Collectibles</Category>
			<Category>Decorative &amp; Holiday</Category>
			<Category>Decorative by Brand</Category>
			<Category>Christopher Radko</Category>
			<Currently>$30.00</Currently>
			<First_Bid>$30.00</First_Bid>
			<Number_of_Bids>0</Number_of_Bids>
			<Bids />
			<Location>its a dry heat</Location>
			<Country>USA</Country>
			<Started>Dec-03-01 18:10:40</Started>
			<Ends>Dec-13-01 18:10:40</Ends>
			<Seller Rating="1035" UserID="rulabula" />
			<Description>brand new beautiful handmade european blown glass ornament from christopher radko. this particular ornament features a snowman paired with a little girl bundled up in here pale blue coat sledding along on a silver and blue sled filled with packages. the ornament is approximately 5_ tall and 4_ wide. brand new and never displayed, it is in its clear plastic packaging and comes in the signature black radko gift box. PLEASE READ CAREFULLY!!!! payment by cashier's check, money order, or personal check. personal checks must clear before shipping. the hold period will be a minimum of 14 days. I ship with UPS and the buyer is responsible for shipping charges. the shipping rate is dependent on both the weight of the package and the distance that package will travel. the minimum shipping/handling charge is $6 and will increase with distance and weight. shipment will occur within 2 to 5 days after the deposit of funds. a $2 surcharge will apply for all USPS shipments if you cannot have or do not want ups service. If you are in need of rush shipping, please let me know and I_will furnish quotes on availability. the BUY-IT-NOW price includes free domestic shipping (international winners and residents of alaska and hawaii receive a credit of like value applied towards their total) and, as an added convenience, you can pay with paypal if you utilize the feature. paypal is not accepted if you win the auction during the course of the regular bidding-I only accept paypal if the buy it now feature is utilized. thank you for your understanding and good luck! Free Honesty Counters powered by Andale! Payment Details See item description and Payment Instructions, or contact seller for more information. Payment Instructions See item description or contact seller for more information.</Description>
		</Item>
		*/
		
		// establish a connection with database
		Connection conn = null;
		
		try {
			// start database connection
			conn = DbManager.getConnection(true);

			// statement for querying, such as grabbing an item's details using ItemID
			Statement s = conn.createStatement();
			ResultSet itemRS = s.executeQuery("SELECT * FROM Items WHERE ItemID = " + itemId);
		
			
			// if such an item exists, get its information
			if(itemRS.next()) {
				String name = itemRS.getString("Name");
				String buyPrice = itemRS.getString("BuyPrice");
				String firstBid = itemRS.getString("FirstBid");
				String started = itemRS.getString("Started");
				String ends = itemRS.getString("Ends");
				String latitude = itemRS.getString("Latitude");
				String longitude = itemRS.getString("Longitude");
				String location = itemRS.getString("Location");
				String country = itemRS.getString("COuntry");
				String description = itemRS.getString("Description");
				String sellerId = itemRS.getString("SellerID");

				started = formatDate(started);
				ends = formatDate(ends);
				
				/*
				ResultSet bidRS = s.executeQuery("SELECT * FROM Bids WHERE ItemID = " + itemId);
				ResultSet categoryRS = s.executeQuery("SELECT * FROM ItemCategories WHERE ItemID = " + itemId);
				ResultSet userRS = s.executeQuery("SELECT * FROM Users WHERE UserID = " + 000000000000000000000);
				*/
				
				/*
				System.out.println(name);
				System.out.println(buyPrice);
				System.out.println(firstBid);
				System.out.println(started);
				System.out.println(ends);
				System.out.println(latitude);
				System.out.println(longitude);
				System.out.println(location);
				System.out.println(country);
				System.out.println(description);
				System.out.println(sellerId);
				*/
			}
			else {
				// no such ItemID found, so return empty string
				return "";
			}

			// close result sets, statements and database connection
			itemRS.close();
			s.close();
			conn.close();
			
		} catch (SQLException ex) {
			System.out.println(ex);
		}
		
		// if any errors, return empty string
		return "";
	}
	
	// given document ID, return corresponding document from IndexSearcher
	public Document getDocument(int docId) throws IOException {
        return searcher.doc(docId);
    }
	
	// given lx, ly, rx, ry (bottom-left coordinates and top-right coordinates, respectively), generate a MySQL polygon region
	public String getPolygon(double lx, double ly, double rx, double ry) {
		return "GeomFromText('Polygon((" + lx + " " + ly + ", " + lx + " " + ry + ", " + rx + " " + ry + ", " + rx + " " + ly + ", " + lx + " " + ly +  "))')";
	}
	
	// given a MySQL Timestamp as a string, reformat it into original XML date format
	public String formatDate(String dateString) {
		
		// set up date formats
		SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-mm-dd HH:mm:ss");
		SimpleDateFormat outputFormat = new SimpleDateFormat("MMM-dd-yy HH:mm:ss");	
		
		// parse the date string if possible; leave it unchanged otherwise
		try {
			// parse in string using input format to create a Date object
			Date date = inputFormat.parse(dateString);
			
			// output Date object using output format and store as string
			dateString = outputFormat.format(date);
		} catch(Exception ex) {
			System.out.println(ex);
		}
		
		return dateString;
	}
	
	// replace all occurrences of '&', '"', ''', '<', and '>' with their &_; counterparts
	public String escapeString(String input) {
		return input.replaceAll("&", "&amp;").replaceAll("\"", "&quot;").replaceAll("'", "&apos;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
	}
	
	public String echo(String message) {
		return message;
	}

}
