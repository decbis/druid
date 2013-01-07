/*
 * Druid - a distributed column store.
 * Copyright (C) 2012  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.metamx.druid.master;

import com.google.common.collect.Lists;
import com.google.common.collect.MinMaxPriorityQueue;
import com.google.common.collect.Sets;
import com.metamx.common.Pair;
import com.metamx.common.logger.Logger;
import com.metamx.druid.client.DataSegment;
import com.metamx.druid.client.DruidServer;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;


/**
 * The BalancerCostAnalyzer will compute the total initial cost of the cluster, with costs defined in
 * computeJointSegmentCosts.  It will then propose to move (pseudo-)randomly chosen segments from their
 * respective initial servers to other servers, chosen greedily to minimize the cost of the cluster.
 */
public class BalancerCostAnalyzer
{
  private static final Logger log = new Logger(BalancerCostAnalyzer.class);
  private static final int MAX_SEGMENTS_TO_MOVE = 5;
  private static final int DAY_IN_MILLIS = 1000 * 60 * 60 * 24;
  private static final int SEVEN_DAYS_IN_MILLIS = 7 * DAY_IN_MILLIS;
  private static final int THIRTY_DAYS_IN_MILLIS = 30 * DAY_IN_MILLIS;

  private List<ServerHolder> serverHolderList;
  private Random rand;
  private DateTime referenceTimestamp;

  private double initialTotalCost;
  private double normalization;
  private double totalCostChange;

  public BalancerCostAnalyzer(DateTime referenceTimestamp)
  {
    this.referenceTimestamp = referenceTimestamp;
    rand = new Random(0);
    totalCostChange = 0;
  }

  public void init(List<ServerHolder> serverHolderList)
  {
    this.initialTotalCost = calculateInitialTotalCost(serverHolderList);
    this.normalization = calculateNormalization(serverHolderList);
    this.serverHolderList = serverHolderList;
  }

  public double getInitialTotalCost()
  {
    return initialTotalCost;
  }

  public double getNormalization()
  {
    return normalization;
  }

  public double getNormalizedInitialCost()
  {
    return initialTotalCost / normalization;
  }

  public double getTotalCostChange()
  {
    return totalCostChange;
  }

  /*
   * Calculates the cost normalization.  This is such that the normalized cost is lower bounded
   * by 1 (e.g. when each segment gets its own compute node).
   */
  private double calculateNormalization(List<ServerHolder> serverHolderList)
  {
    double cost = 0;
    for (ServerHolder server : serverHolderList) {
      DataSegment[] segments = server.getServer().getSegments().values().toArray(new DataSegment[]{});
      for (int i = 0; i < segments.length; ++i) {
        cost += computeJointSegmentCosts(segments[i], segments[i]);
      }
    }
    return cost;
  }

  // Calculates the initial cost of the Druid segment configuration.
  private double calculateInitialTotalCost(List<ServerHolder> serverHolderList)
  {
    double cost = 0;
    for (ServerHolder server : serverHolderList) {
      DataSegment[] segments = server.getServer().getSegments().values().toArray(new DataSegment[]{});
      for (int i = 0; i < segments.length; ++i) {
        for (int j = i; j < segments.length; ++j) {
          cost += computeJointSegmentCosts(segments[i], segments[j]);
        }
      }
    }
    return cost;
  }

  /*
   * This defines the unnormalized cost function between two segments.  There is a base cost given by
   * the minimum size of the two segments and additional penalties.
   * recencyPenalty: it is more likely that recent segments will be queried together
   * dataSourcePenalty: if two segments belong to the same data source, they are more likely to be involved
   * in the same queries
   * gapPenalty: it is more likely that segments close together in time will be queried together
   */
  public double computeJointSegmentCosts(DataSegment segment1, DataSegment segment2)
  {
    double cost = 0;
    Interval gap = segment1.getInterval().gap(segment2.getInterval());

    double baseCost = Math.min(segment1.getSize(), segment2.getSize());
    double recencyPenalty = 1;
    double dataSourcePenalty = 1;
    double gapPenalty = 1;

    if (segment1.getDataSource().equals(segment2.getDataSource())) {
      dataSourcePenalty = 2;
    }

    double maxDiff = Math.max(
        referenceTimestamp.getMillis() - segment1.getInterval().getEndMillis(),
        referenceTimestamp.getMillis() - segment2.getInterval().getEndMillis()
    );
    if (maxDiff < SEVEN_DAYS_IN_MILLIS) {
      recencyPenalty = 2 - maxDiff / SEVEN_DAYS_IN_MILLIS;
    }

    // gap is null if the two segment intervals overlap or if they're adjacent
    if (gap == null) {
      gapPenalty = 2;
    } else {
      long gapMillis = gap.toDurationMillis();
      if (gapMillis < THIRTY_DAYS_IN_MILLIS) {
        gapPenalty = 2 - gapMillis / THIRTY_DAYS_IN_MILLIS;
      }
    }

    cost = baseCost * recencyPenalty * dataSourcePenalty * gapPenalty;

    return cost;
  }

  /*
   * These could be anonymous in BalancerCostAnalyzerHelper
   * Their purpose is to unify the balance/assignment code since a segment that has not yet been assigned
   * does not have a source server.
   */
  public class NullServerHolder extends ServerHolder
  {
    public NullServerHolder()
    {
      super(null, null);
    }

    public class NullDruidServer extends DruidServer
    {
      public NullDruidServer()
      {
        super(null, null, 0, null, null);
      }

      @Override
      public boolean equals(Object o)
      {
        return false;
      }
    }

    @Override
    public DruidServer getServer()
    {
      return new NullDruidServer();
    }
  }

  public class BalancerCostAnalyzerHelper
  {
    private MinMaxPriorityQueue<Pair<Double, ServerHolder>> costsServerHolderPairs;
    private List<ServerHolder> serverHolderList;
    private DataSegment proposalSegment;
    private ServerHolder fromServerHolder;
    private Set<BalancerSegmentHolder> segmentHoldersToMove;
    private double currCost;

    public MinMaxPriorityQueue<Pair<Double, ServerHolder>> getCostsServerHolderPairs()
    {
      return costsServerHolderPairs;
    }

    public List<ServerHolder> getServerHolderList()
    {
      return serverHolderList;
    }

    public DataSegment getProposalSegment()
    {
      return proposalSegment;
    }

    public ServerHolder getFromServerHolder()
    {
      return fromServerHolder;
    }

    public Set<BalancerSegmentHolder> getSegmentHoldersToMove()
    {
      return segmentHoldersToMove;
    }

    public double getCurrCost()
    {
      return currCost;
    }

    public BalancerCostAnalyzerHelper(
        List<ServerHolder> serverHolderList,
        DataSegment proposalSegment
    )
    {
      this(serverHolderList, proposalSegment, new NullServerHolder(), Sets.<BalancerSegmentHolder>newHashSet());
    }

    public BalancerCostAnalyzerHelper(
        List<ServerHolder> serverHolderList,
        DataSegment proposalSegment,
        ServerHolder fromServerHolder,
        Set<BalancerSegmentHolder> segmentHoldersToMove
    )
    {
      // Just need a regular priority queue for the min. element.
      this.costsServerHolderPairs = MinMaxPriorityQueue.orderedBy(
          new Comparator<Pair<Double, ServerHolder>>()
          {
            @Override
            public int compare(
                Pair<Double, ServerHolder> o,
                Pair<Double, ServerHolder> o1
            )
            {
              return Double.compare(o.lhs, o1.lhs);
            }
          }
      ).create();
      this.serverHolderList = serverHolderList;
      this.proposalSegment = proposalSegment;
      this.fromServerHolder = fromServerHolder;
      this.segmentHoldersToMove = segmentHoldersToMove;
      this.currCost = 0;
    }

    public void computeAllCosts()
    {
      // The contribution to the total cost of a given server by proposing to move the segment to that server is...
      for (ServerHolder server : serverHolderList) {
        double cost = 0f;
        // the sum of the costs of other (inclusive) segments on the server
        for (DataSegment segment : server.getServer().getSegments().values()) {
          cost += computeJointSegmentCosts(proposalSegment, segment);
        }

        // plus the self cost if the proposed new server is different
        if (!fromServerHolder.getServer().equals(server.getServer())) {
          cost += computeJointSegmentCosts(proposalSegment, proposalSegment);
        }

        // plus the costs of segments that will be moved.
        Iterator it = segmentHoldersToMove.iterator();
        while (it.hasNext()) {
          BalancerSegmentHolder segmentToMove = (BalancerSegmentHolder) it.next();
          if (server.getServer().equals(segmentToMove.getToServer())) {
            cost += computeJointSegmentCosts(proposalSegment, segmentToMove.getSegment());
          }
          if (server.getServer().equals(segmentToMove.getFromServer())) {
            cost -= computeJointSegmentCosts(proposalSegment, segmentToMove.getSegment());
          }
        }

        // currCost keeps track of the current cost for that server (so we can compute the cost change).
        if (fromServerHolder.getServer().equals(server.getServer())) {
          currCost = cost;
        }

        // Only enter the queue if the server has enough size.
        if (proposalSegment.getSize() < server.getAvailableSize()) {
          costsServerHolderPairs.add(Pair.of(cost, server));
        }

      }
    }

  }

  public Set<BalancerSegmentHolder> findSegmentsToMove()
  {
    Set<BalancerSegmentHolder> segmentHoldersToMove = Sets.newHashSet();
    Set<DataSegment> movingSegments = Sets.newHashSet();

    int counter = 0;

    while (segmentHoldersToMove.size() < MAX_SEGMENTS_TO_MOVE && counter < 3 * MAX_SEGMENTS_TO_MOVE) {
      counter++;
      ServerHolder fromServerHolder = serverHolderList.get(rand.nextInt(serverHolderList.size()));
      List<DataSegment> segments = Lists.newArrayList(fromServerHolder.getServer().getSegments().values());
      if (segments.size() == 0) {
        continue;
      }
      DataSegment proposalSegment = segments.get(rand.nextInt(segments.size()));
      if (movingSegments.contains(proposalSegment)) {
        continue;
      }

      BalancerCostAnalyzerHelper helper = new BalancerCostAnalyzerHelper(
          serverHolderList,
          proposalSegment,
          fromServerHolder,
          segmentHoldersToMove
      );
      helper.computeAllCosts();

      Pair<Double, ServerHolder> minPair = helper.getCostsServerHolderPairs().pollFirst();

      if (minPair.rhs != null && !minPair.rhs.equals(fromServerHolder)) {
        movingSegments.add(proposalSegment);
        segmentHoldersToMove.add(
            new BalancerSegmentHolder(
                fromServerHolder.getServer(),
                minPair.rhs.getServer(),
                proposalSegment
            )
        );
        totalCostChange += helper.getCurrCost() - minPair.lhs;
      }
    }

    return segmentHoldersToMove;
  }
}

