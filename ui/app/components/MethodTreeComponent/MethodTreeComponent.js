import React, { Component } from 'react';
import StacklineDetail from 'components/StacklineDetailComponent';
import StacklineStats from 'components/StacklineStatsComponent';
import { withRouter } from 'react-router';
import { ScrollSync, AutoSizer, Grid } from 'react-virtualized';
import debounce from 'utils/debounce';

import styles from './MethodTreeComponent.css';
import HotMethodNode from '../../pojos/HotMethodNode';
import 'react-virtualized/styles.css';
import http from 'utils/http';
import {objectToQueryParams} from 'utils/UrlUtils';
import Loader from 'components/LoaderComponent';


const noop = () => {};

const rightColumnWidth = 150;
const everythingOnTopHeight = 255;
const filterBoxHeight = 87;
const stackEntryHeight = 25;

//Input is expected to be Array of (nodeIndex, callCount)   and
//output returned is an array of objects of type HotMethodNode
//This function aggregates nodes with same name+lineNo to be rendered and same name for first layer
//in hotmethodView. As part of aggregation their sampledCallCounts are added and respective parents added in a list
//with sampledCallCounts caused by them
const dedupeNodes = (allNodes) => (nodesWithCallCount) => {
  let dedupedNodes = {};
  for(let i=0; i<nodesWithCallCount.length; i++){
    let nodeWithCallCount = nodesWithCallCount[i];
    const nodeIndex = nodeWithCallCount[0];
    const node = allNodes[nodeIndex];
    const sampledCallCount = nodeWithCallCount[1];
    if(! node.hasParent()) break;
    let renderNode;
    if(sampledCallCount === undefined)
      renderNode = new HotMethodNode(true, node.lineNo, node.name, node.onCPU, [[nodeIndex, node.onCPU]]);
    else
      renderNode = new HotMethodNode(false, node.lineNo, node.name, sampledCallCount, [[node.parent, sampledCallCount]]);
    const key = renderNode.identifier();
    if (!dedupedNodes[key]) {
      dedupedNodes[key] = renderNode;
    } else {
      dedupedNodes[key].sampledCallCount += renderNode.sampledCallCount;
      dedupedNodes[key].parentsWithSampledCallCount = [...dedupedNodes[key].parentsWithSampledCallCount, ...renderNode.parentsWithSampledCallCount];
    }
  }
  return Object.keys(dedupedNodes).map(k => dedupedNodes[k]).sort((a, b) => b.sampledCallCount - a.sampledCallCount);
};

const getTextWidth = function(text, font) {
  // re-use canvas object for better performance
  const canvas = getTextWidth.canvas || (getTextWidth.canvas = document.createElement("canvas"));
  const context = canvas.getContext("2d");
  context.font = font;
  const metrics = context.measureText(text);
  return metrics.width;
};

class CallTreeStore {
  constructor(url) {
    this.nodes = {};
    this.methodLookup = {};
    this.url = url;
  }

  flatten(root) {
    //sorting logic on Object.entries if any
    Object.entries(root).forEach(([k, v]) => {
      if (k !== 'method_lookup') {
        this.nodes[parseInt(k)] = [v.data, v.chld ? Object.keys(v.chld).map(c => parseInt(c)) : undefined];
        if (v.chld) {
          this.flatten(v.chld, this.nodes);
        }
      }
    });
  }

  getNameWithArgs(uniqueId) {
    return this.methodLookup[this.nodes[uniqueId][0][0]] + ' ' + this.nodes[uniqueId][0][1];
  }

  getChildrenAsync(uniqueId) {
    if (uniqueId >= this.nodes.length) { // never supposed to be true
      return [];
    }
    if (this.nodes[uniqueId] && this.nodes[uniqueId][1]) {
      return Promise.resolve(this.nodes[uniqueId][1]);
    }
    const body = (uniqueId === -1) ? [] : [uniqueId];
    return http.post(this.url, body).then(
      resp => {
        this.flatten(resp);
        Object.assign(this.methodLookup, resp['method_lookup']);
        if (uniqueId === -1) {
          this.nodes[uniqueId] = [null, Object.keys(resp).filter((k) => k !== 'method_lookup').map(k => parseInt(k))];
        }
        return this.nodes[uniqueId][1];
      }
    );

  }

  getSampleCount(uniqueId) {
    return this.nodes[uniqueId][0][2];
  }

  getChildren(uniqueId) {
    if (this.nodes[uniqueId]) {
      return this.nodes[uniqueId][1];
    }
  }
}

class MethodTreeComponent extends Component {
  constructor (props) {
    super(props);
    this.state = {
      itemCount: 0,
      req: {url: '', status: 'PENDING', err: ''}
    };

    this.containerWidth = 0;
    this.opened = {}; // keeps track of all opened/closed nodes
    this.highlighted = {}; //keeps track of all highlighted nodes
    this.allNodes = {};
    this.stacklineDetailCellRenderer = this.stacklineDetailCellRenderer.bind(this);
    this.stacklineStatCellRenderer = this.stacklineStatCellRenderer.bind(this);

    this.toggle = this.toggle.bind(this);
    this.handleFilterChange = this.handleFilterChange.bind(this);
    this.highlight = this.highlight.bind(this);
    this.debouncedHandleFilterChange = debounce(this.handleFilterChange, 250);

    this.getMaxWidthOfRenderedStacklines = this.getMaxWidthOfRenderedStacklines.bind(this);
    this.getRenderedDescendantCountForListItem = this.getRenderedDescendantCountForListItem.bind(this);
    this.getRenderedChildrenCountForListItem = this.getRenderedChildrenCountForListItem.bind(this);
    this.isNodeHavingChildren = this.isNodeHavingChildren.bind(this);

    this.dedupeNodes = dedupeNodes(props.allNodes);
    this.renderData = [];
    this.state.itemCount = this.renderData.length;

    this.setup(props.containerWidth);
  }

  componentDidMount() {
    const {app, cluster, proc, workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const queryParams = objectToQueryParams({start: profileStart, duration: profileDuration, autoExpand: true});
    const url = `/api/callers/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
    this.callTreeStore = new CallTreeStore(url);
    this.getRenderData(this.callTreeStore.getChildrenAsync(-1), this.props.filterKey, -1, false).then(subTreeRenderData => {
      this.renderData = subTreeRenderData;
      this.setState({
        itemCount: this.renderData.length
      });
    });
  }

  componentDidUpdate(prevProps) {
    const {app, cluster, proc, workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const {profileStart: prevProfileStart, profileDuration: prevProfileDuration} = prevProps.location.query;
    const queryParams = objectToQueryParams({start: profileStart, duration: profileDuration, autoExpand: true});
    this.callTreeStore.url = `/api/callers/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
    if(prevProfileStart !== profileStart || prevProfileDuration !== profileDuration || prevProps.traceName !== this.props.traceName){
      this.getRenderData(this.callTreeStore.getChildrenAsync(-1), this.props.filterKey, -1, false).then(subTreeRenderData => {
        this.renderData = subTreeRenderData;
        this.setState({
          itemCount: this.renderData.length
        });
      });
    }
  }

  componentWillUpdate(nextProps) {
    this.setup(nextProps.containerWidth);
  }

  setup(containerWidth) {
    if(containerWidth > 0 && containerWidth !== this.containerWidth) {
      this.containerWidth = containerWidth;
    }
  }

  render () {
    // console.log('this.containerWidth: ', this.containerWidth);
    // if(this.containerWidth === 0) {
    //   return null;
    // }

    // if (!this.state.req.status) return null;
    //
    // if (this.state.req.status === 'PENDING') {
    //   return (
    //     <div>
    //       <h4 style={{textAlign: 'center'}}>Please wait, coming right up!</h4>
    //       <Loader/>
    //     </div>
    //   );
    // }
    //
    // if (this.state.req.status === 'ERROR') {
    //   return (
    //     <div>
    //       <h2 style={{textAlign: 'center'}}>Failed to fetch the data. Please refresh or try again later</h2>
    //     </div>
    //   );
    // }

    const filterText = this.props.location.query[this.props.filterKey];
    const { nextNodesAccessorField } = this.props;
    const containerHeight = window.innerHeight - everythingOnTopHeight; //subtracting height of everything above the container
    const gridHeight = containerHeight - filterBoxHeight; //subtracting height of filter box
    return (
      <div>
        <div>
          <ScrollSync>
            {({ clientHeight, clientWidth, onScroll, scrollHeight, scrollLeft, scrollTop, scrollWidth }) => (
              <div className={styles.GridRow}>
                <div className={styles.LeftGridContainer}>
                  <div className={styles.GridHeader}>
                    <div className={`mdl-textfield mdl-js-textfield ${styles.filterBox}`}>
                      <label htmlFor="method_filter">
                        {nextNodesAccessorField === 'parent' ? "Filter hot methods" : "Filter root callers"}
                      </label>
                      <input
                        className={`mdl-textfield__input`}
                        type="text"
                        defaultValue={filterText}
                        autoFocus
                        onChange={this.debouncedHandleFilterChange}
                        id="method_filter"
                      />
                    </div>
                  </div>
                  <div className={styles.GridBody}>
                    <AutoSizer disableHeight>
                      {({ width }) => (
                        <Grid
                          columnCount={1}
                          columnWidth={Math.max(width, this.getMaxWidthOfRenderedStacklines())}
                          height={gridHeight}
                          width={width}
                          rowCount={this.state.itemCount}
                          rowHeight={stackEntryHeight}
                          cellRenderer={this.stacklineDetailCellRenderer}
                          className={styles.LeftGrid}
                          overscanRowCount={10}
                          onScroll={onScroll}
                          ref={el => this.stacklineDetailGrid = el}
                        />
                      )}
                    </AutoSizer>
                  </div>
                </div>
                <div className={styles.RightGridContainer}>
                  <div className={styles.GridHeader}>
                    <label>Samples</label>
                  </div>
                  <div className={styles.GridBody}>
                    <Grid
                      columnCount={1}
                      columnWidth={rightColumnWidth}
                      height={gridHeight}
                      width={rightColumnWidth}
                      rowCount={this.state.itemCount}
                      rowHeight={stackEntryHeight}
                      cellRenderer={this.stacklineStatCellRenderer}
                      className={styles.RightGrid}
                      overscanRowCount={10}
                      scrollTop={scrollTop}
                      ref={el => this.stacklineStatGrid = el}
                    />
                  </div>
                </div>
              </div>
            )}
          </ScrollSync>
        </div>
        {!this.state.itemCount && (
          <div style={{flex: "1 1 auto", marginTop: "-" + (gridHeight) + "px"}} className={styles.alert}>No results</div>
        )}
      </div>
    );
  }

  toggle (listIdx) {
    //TODO: Pick a lock for the listIdx, i.e. if any toggle is pending on same uniqueId show a snackBar
    const rowData = this.renderData[listIdx];
    const uniqueId = rowData[0];

    if (!this.opened[uniqueId]) {
      //expand
      this.getRenderData(this.callTreeStore.getChildrenAsync(uniqueId), null, rowData[2], rowData[3] > 1).then(subTreeRenderData => {
        this.renderData.splice(listIdx + 1, 0, ...subTreeRenderData);
        this.opened[uniqueId] = !this.opened[uniqueId];
        this.setState({
          itemCount: this.renderData.length
        });
      });
    } else {
      //collapse
      const descendants = this.getRenderedDescendantCountForListItem(listIdx);
      if (descendants > 0) {
        this.renderData.splice(listIdx + 1, descendants);
      }
      this.opened[uniqueId] = !this.opened[uniqueId];
      this.setState({
        itemCount: this.renderData.length
      });
    }
  }

  highlight (path) {
    if (path in this.highlighted) {
      //highlighted node, remove highlight
      delete this.highlighted[path];
    } else {
      // identifying already highlighted children of path
      const highlightedChildren = Object.keys(this.highlighted)
        .filter(highlight => highlight.startsWith(path));
      if (highlightedChildren.length) {
        // delete highlighted children
        highlightedChildren.forEach((p) => {
          delete this.highlighted[p];
        });
      }

      // identifying already highlighted parents of path, this will always be 1 at max
      const highlightedParents = Object.keys(this.highlighted)
        .filter(highlight => path.startsWith(highlight));
      if (highlightedParents.length) {
        // delete highlighted parents
        highlightedParents.forEach((p) => {
          delete this.highlighted[p];
        });
      }

      this.highlighted[path] = true;
    }

    if(this.stacklineDetailGrid && this.stacklineStatGrid) {
      this.stacklineDetailGrid.forceUpdate();
      this.stacklineStatGrid.forceUpdate();
    }
  }

  handleFilterChange (e) {
    const { pathname, query } = this.props.location;
    this.props.router.push({ pathname, query: { ...query, [this.props.filterKey]: e.target.value } });
    this.getRenderData(this.callTreeStore.getChildrenAsync(-1), e.target.value, -1, false).then(subTreeRenderData => {
      this.renderData = subTreeRenderData;
      this.setState({
        itemCount: this.renderData.length
      });
    });
  }

  stacklineDetailCellRenderer ({ rowIndex, style }) {
    let rowData = this.renderData[rowIndex];
    let uniqueId = rowData[0];

    let newNodeIndexes;

    //This condition is equivalent to (n instanceOf HotMethodNode)
    //since nextNodesAccessorField is = parent in hot method view and
    //Node type for dedupedNodes is HotMethodNode from above
    // if (this.props.nextNodesAccessorField === 'parent') {
    //   newNodeIndexes = n.parentsWithSampledCallCount;
    //   const lineNoOrNot = (n.belongsToTopLayer)? '' : ':' + n.lineNo;
    //   displayName = displayName + lineNoOrNot;
    //   displayNameWithArgs = displayNameWithArgs + lineNoOrNot;
    // } else {
    //   // using the index i because in call tree the name of sibling nodes
    //   // can be same, react will throw up, argh!
    //   newNodeIndexes = n.children;
    //   displayName = displayName + ':' + n.lineNo;
    //   displayNameWithArgs = displayNameWithArgs + ':' + n.lineNo;
    // }
    const isHighlighted = Object.keys(this.highlighted)
      .filter(highlight => highlight.startsWith(uniqueId));
    const displayName = this.callTreeStore.getNameWithArgs(uniqueId);
    return (
      <StacklineDetail
        key={uniqueId}
        style={{...style, height: stackEntryHeight, whiteSpace: 'nowrap'}}
        listIdx={rowIndex}
        nodename={displayName}
        stackline={displayName}
        indent={rowData[2]}
        nodestate={this.opened[uniqueId]}
        highlight={isHighlighted.length}
        subdued={rowData[3] === 1}
        onHighlight={this.highlight.bind(this, uniqueId)}
        onClick={this.toggle.bind(this, rowIndex)}>
      </StacklineDetail>

    );
  }

  stacklineStatCellRenderer({ rowIndex, style }) {
    let rowData = this.renderData[rowIndex];
    let uniqueId = rowData[0];
    const percentageDenominator = this.callTreeStore.getChildren(-1).reduce((sum, id) => sum + this.callTreeStore.getSampleCount(id), 0);
    const countToDisplay = this.callTreeStore.getSampleCount(uniqueId);
    const onStackPercentage = Number((countToDisplay * 100) / percentageDenominator).toFixed(2);
    const isHighlighted = Object.keys(this.highlighted)
      .filter(highlight => highlight.startsWith(uniqueId));

    return (
      <StacklineStats
        key={uniqueId}
        style={style}
        listIdx={rowIndex}
        samples={countToDisplay}
        samplesPct={onStackPercentage}
        highlight={isHighlighted.length}
        subdued={rowData[3] === 1}>
      </StacklineStats>
    );
  }

  getRenderData(asyncIds, filterText, parentIndent, parentHasSiblings) {
    return new Promise(resolve => {
        asyncIds.then(ids => {
        if(ids) {
          ids.sort((a, b) => this.callTreeStore.getSampleCount(b) - this.callTreeStore.getSampleCount(a));
          const asyncIdsRenderData = ids.map((id) => new Promise(resolve => {
            const indent = parentIndent === -1 ? 0 : ((parentHasSiblings || ids.length > 1 ) ? parentIndent + 10 : parentIndent + 6);
            const stackEntryWidth = getTextWidth(this.callTreeStore.getNameWithArgs(id), "14px Arial") + 28 + indent; //28 is space taken up by icons
            let renderDataList = [[id, null, indent, ids.length, stackEntryWidth]];
            if (filterText && indent === 0 && !this.callTreeStore.getNameWithArgs(id).match(new RegExp(filterText, 'i'))) {
              renderDataList = [];
            }
            if (ids.length === 1 || this.opened[id]) {
              this.opened[id] = true;
              this.getRenderData(this.callTreeStore.getChildrenAsync(id), filterText, indent, ids.length > 1).then(subTreeRenderDataList => {
                console.log('subTreeRenderDataList: ', subTreeRenderDataList, ' for id: ', id);
                resolve(renderDataList.concat(subTreeRenderDataList));
              });
            } else {
              resolve(renderDataList);
            }
          }));
          Promise.all(asyncIdsRenderData).then(idsRenderData => {
            resolve(idsRenderData.reduce((accumRenderData, idRenderData) => accumRenderData.concat(idRenderData), []));
          })
        }else{
          resolve([]);
        }
      });
    });
  }

  // getRenderData (nodeIndexes = [], filterText, parentHasSiblings, parentIndent) {
  //   const renderStack = [];
  //   renderStack.push({
  //     gen: {
  //       nis: nodeIndexes, //indexes of first-level nodes in the tree subject to de-duplication
  //       p_ind: parentIndent, //indentation of parent node
  //       p_sib: parentHasSiblings, //parentHasSiblings
  //     }
  //   });
  //   const renderData = [];
  //
  //   const getOriginalKeys = function (nodes){
  //     const keys = [];
  //     if (this.props.nextNodesAccessorField === 'parent') {
  //       return nodes.forEach(n => keys.concat(n.parts.map(part => part.uniqueId)));
  //     } else {
  //       return nodes.map(n => n.uniqueId);
  //     }
  //   }.bind(this);
  //
  //   while(renderStack.length > 0) {
  //     let se = renderStack.pop();
  //     if (se.gen) {
  //       // only need to de-dupe for bottom-up not top-down,
  //       // hence the ternary :/
  //       // const dedupedNodes = this.props.nextNodesAccessorField === 'parent'
  //       //   ? this.dedupeNodes(se.gen.nis)
  //       //   : se.gen.nis.map((nodeIndex) => this.allNodes[nodeIndex]).slice().sort((a, b) => b.onStack - a.onStack);
  //
  //       const nodeData = this.props.nextNodesAccessorField === 'parent' ? this.hotMethodNodes : this.allNodes;
  //       const processedNodes = nodeIndexes.map((nodeIndex) => nodeData[nodeIndex]).slice().sort((a, b) => b.onStack - a.onStack);
  //       // this.fetchNodes(this.state.req.url, getOriginalKeys(processedNodes));
  //       //Indent should always be zero if no parent
  //       //Otherwise, if parent has siblings or if this node has siblings, do a major indent of the nodes, minor indent otherwise
  //       const indent = se.p_ind === 0 ? 0 : ((se.gen.p_sib || processedNodes.length > 1 ) ? se.gen.p_ind + 10 : se.gen.p_ind + 3);
  //       renderStack.push({
  //        node: {
  //           dn: processedNodes, //first-level nodes
  //           ind: indent, //indentation to be applied to rendered node
  //           idx: 0, //index in array "dn" to identify the node to render,
  //         }
  //       });
  //     } else {
  //       if(se.node.idx >= se.node.dn.length) {
  //         continue;
  //       }
  //       const n = se.node.dn[se.node.idx];
  //       //Node has been retrieved so it is safe to increment index and push stack entry back in render stack
  //       //Fields from this entry will be read further in this iteration but not modified beyond this point, avoiding un-necessary object copy
  //       se.node.idx++;
  //       //After index increment, stack entry refers to next sibling, pushing it now itself on stack
  //       //This ensures that stack entries of children of the current node are pushed later and hence processed earlier
  //       renderStack.push(se);
  //
  //       let displayName = this.methodLookup[n.name][0];
  //       //If this is a first-level node(p_pth will be empty) and filter is applied, skip rendering of node if display name does not match the filter
  //       if(filterText && se.ind === 0 && !displayName.match(new RegExp(filterText, 'i'))) {
  //         continue;
  //       }
  //
  //       let uniqueId, newNodeIndexes;
  //       //This condition is equivalent to (n instanceOf HotMethodNode)
  //       //since nextNodesAccessorField is = parent in hot method view and
  //       //Node type for dedupedNodes is HotMethodNode from above
  //       if (this.props.nextNodesAccessorField === 'parent') {
  //         uniqueId = n.index;
  //         newNodeIndexes = n.parentsWithSampledCallCount;
  //         const lineNoOrNot = (n.belongsToTopLayer)? '' : ':' + n.lineNo;
  //         displayName = displayName + lineNoOrNot;
  //       } else {
  //         uniqueId = n.index;
  //         newNodeIndexes = n.children;
  //         displayName = displayName + ':' + n.lineNo;
  //       }
  //
  //       // If only single node is being rendered, expand the node
  //       // Or if the node has no children, then expand the node, so that expanded icon is rendered against this node
  //       if(se.node.dn.length === 1 || newNodeIndexes.length === 0) {
  //         this.opened[uniqueId] = true;
  //       }
  //
  //       const stackEntryWidth = getTextWidth(displayName, "14px Arial") + 28 + se.node.ind; //28 is space taken up by icons
  //       const nodeData = [uniqueId, n, se.node.ind, se.node.dn.length, stackEntryWidth];
  //       renderData.push(nodeData);
  //
  //       if(this.opened[uniqueId] && newNodeIndexes) {
  //         renderStack.push({
  //           gen: {
  //             nis: newNodeIndexes,
  //             p_ind: se.node.ind,
  //             p_sib: se.node.dn.length > 1
  //           }
  //         });
  //       }
  //     }
  //   }
  //   return renderData;
  // }

  // getInitialRenderData(filterText) {
  //   const { nextNodesAccessorField } = this.props;
  //   let nodeIndexes;
  //   if (nextNodesAccessorField === 'parent') {
  //     nodeIndexes = this.nodeIndexes.map((nodeIndex) => [nodeIndex, undefined]);
  //   } else {
  //     nodeIndexes = this.nodeIndexes;
  //   }
  //   return this.getRenderData(nodeIndexes, filterText, '', false, 0);
  // }

  getRenderedDescendantCountForListItem(listIdx) {
    let currIdx = listIdx;
    let toVisit = this.getRenderedChildrenCountForListItem(currIdx);
    while(toVisit > 0) {
      toVisit--;
      currIdx++;
      toVisit += this.getRenderedChildrenCountForListItem(currIdx);
    }
    return currIdx - listIdx;
  }

  getRenderedChildrenCountForListItem(listIdx) {
    let children = 0;
    let rowdata = this.renderData[listIdx];
    if(rowdata) {
      const uniqueId = rowdata[0];
      if(this.opened[uniqueId]) {
        if(this.isNodeHavingChildren(rowdata[1])) {
          //At least one rendered child item is going to be present for this item
          //Cannot rely on childNodeIndexes(calculated in isNodeHavingChildren method) to get count of children because actual rendered children can be lesser after deduping of nodes for hot method tree
          let child_rowdata = this.renderData[listIdx + 1];
          if(child_rowdata) {
            return child_rowdata[3]; //this is siblings count of child node which implies children count for parent node
          } else {
            console.error("This should never happen. If list item is expanded and its childNodeIndexes > 0, then at least one more item should be present in renderdata list")
          }
        }
      }
    }
    return children;
  }

  isNodeHavingChildren(node) {
    if(this.props.nextNodesAccessorField === 'parent') {
      let childNodeIndexes = node.parentsWithSampledCallCount;
      if(childNodeIndexes && childNodeIndexes.length > 0) {
        if(childNodeIndexes.length !== 1) {
          return true;
        } else {
          //If this has only one childnodeindex and that is "0" node => corresponds to root node which is not rendered in UI
          //"if(! node.hasParent()) break;" condition in dedupeNodes method ensure above node is not rendered
          return childNodeIndexes[0][0] !== 0;
        }
      } else {
        return false;
      }
    } else {
      return (node.children && node.children.length > 0);
    }
  }

  getMaxWidthOfRenderedStacklines() {
    let maxWidthOfRenderedStacklines = 0;
    for(let i = 0;i < this.renderData.length;i++) {
      if (maxWidthOfRenderedStacklines < this.renderData[i][4]) {
        maxWidthOfRenderedStacklines = this.renderData[i][4];
      }
    }
    maxWidthOfRenderedStacklines += 10; //added some buffer
    const minGridWidth = this.containerWidth - rightColumnWidth - 15;
    return maxWidthOfRenderedStacklines < minGridWidth ? minGridWidth : maxWidthOfRenderedStacklines;
  }
  static workTypeMap = {
    cpu_sample_work: 'cpu-sampling',
  };
}

MethodTreeComponent.propTypes = {
  allNodes: React.PropTypes.array,
  nextNodesAccessorField: React.PropTypes.string.isRequired,
  filterKey: React.PropTypes.string
};

export default withRouter(MethodTreeComponent);
