package plugins.WebOfTrust;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import thomasmarkus.nl.freenet.graphdb.EdgeWithProperty;
import thomasmarkus.nl.freenet.graphdb.H2Graph;

public class ScoreComputer {

	H2Graph graph;

	protected static final int capacities[] = {
		100,// Rank 0 : Own identities
		40,     // Rank 1 : Identities directly trusted by ownIdenties
		16, // Rank 2 : Identities trusted by rank 1 identities
		6,      // So on...
		2,
		1       // Every identity above rank 5 can give 1 point
	};          // Identities with negative score have zero capacity

	public ScoreComputer(H2Graph graph)
	{
		this.graph = graph;
	}

	public void compute(String ownIdentityID) throws SQLException
	{
		//the trust for rank+1 is the sum of (capacity*score) for all the rank peers
		Set<Long> seen_vertices = new HashSet<Long>();
		Set<Long> pool = new HashSet<Long>(graph.getVertexByPropertyValue("id", ownIdentityID));

		Map<Long, Integer> vertexToScore = new HashMap<Long, Integer>();
		
		for(int rank=0; rank < 6; rank++)
		{
			System.out.println("rank: " + rank);

			Set<Long> next_pool = new HashSet<Long>();

			for(long vertex_id : pool)
			{
				seen_vertices.add(vertex_id);
				
				if (!vertexToScore.containsKey(vertex_id) || vertexToScore.get(vertex_id) > 0)
				{
					System.out.print(".");
					List<EdgeWithProperty> edges = graph.getOutgoingEdgesWithProperty(vertex_id, "score");

					if (rank == 0) vertexToScore.put(vertex_id, 100);
					
					for(EdgeWithProperty edge : edges)	//gather all connected nodes, filter those already seen
					{
						int score = Integer.parseInt(edge.value);

						if (!vertexToScore.containsKey(edge.vertex_to)) vertexToScore.put(edge.vertex_to, 0);
						int updated_score = vertexToScore.get(edge.vertex_to) + (int) Math.round(score*(capacities[rank]/100.0));
						vertexToScore.put(edge.vertex_to, updated_score);
						next_pool.add(edge.vertex_to);
					}
				}
			}

			//normalize score of next pool participants
			for(Long vertex_id : next_pool)
			{
				normalize(vertexToScore, vertex_id);
			}
			
			//store the calculated scores for the nodes in the graph db
			for(Long vertex_id : pool)
			{
				graph.updateVertexProperty(vertex_id, "score."+ownIdentityID,  Long.toString(normalize(vertexToScore, vertex_id)));
			}

			Iterator<Long> next_pool_iter = next_pool.iterator();
			while(next_pool_iter.hasNext())
			{
				long vector_id = next_pool_iter.next();
				if (vertexToScore.get(vector_id) <= 0) next_pool_iter.remove();
			}

			//don't re-consider vertices which we've already seen in the next iteration
			next_pool.removeAll(seen_vertices);
			
			System.out.println();
			System.out.println("I will consider " + next_pool.size() + " identities for next rank " + (rank+1));
			pool = next_pool; //use the identities from this rank for the next rank
		}
	}

	private static long normalize(Map<Long, Integer> vertexToScore, long vertex_id)
	{
		final int final_score = Math.max(Math.min(vertexToScore.get(vertex_id),100), -100);
		vertexToScore.put(vertex_id, final_score);
		return final_score;
	}

}
