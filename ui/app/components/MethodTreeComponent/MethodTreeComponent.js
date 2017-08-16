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
const everythingOnTopHeight = 160;
const filterBoxHeight = 87;
const stackEntryHeight = 25;

const KEY = 0;
const DATA = 1;
const LEVEL = 2;

//Input is expected to be Array of (nodeIndex, callCount)   and
//output returned is an array of objects of type HotMethodNode
//This function aggregates nodes with same name+lineNo to be rendered and same name for first layer
//in hotmethodView. As part of aggregation their sampledCallCounts are added and respective parents added in a list
//with sampledCallCounts caused by them
// const dedupeNodes = (allNodes) => (nodesWithCallCount) => {
//   let dedupedNodes = {};
//   for(let i=0; i<nodesWithCallCount.length; i++){
//     let nodeWithCallCount = nodesWithCallCount[i];
//     const nodeIndex = nodeWithCallCount[0];
//     const node = allNodes[nodeIndex];
//     const sampledCallCount = nodeWithCallCount[1];
//     if(! node.hasParent()) break;
//     let renderNode;
//     if(sampledCallCount === undefined)
//       renderNode = new HotMethodNode(true, node.lineNo, node.name, node.onCPU, [[nodeIndex, node.onCPU]]);
//     else
//       renderNode = new HotMethodNode(false, node.lineNo, node.name, sampledCallCount, [[node.parent, sampledCallCount]]);
//     const key = renderNode.identifier();
//     if (!dedupedNodes[key]) {
//       dedupedNodes[key] = renderNode;
//     } else {
//       dedupedNodes[key].sampledCallCount += renderNode.sampledCallCount;
//       dedupedNodes[key].parentsWithSampledCallCount = [...dedupedNodes[key].parentsWithSampledCallCount, ...renderNode.parentsWithSampledCallCount];
//     }
//   }
//   return Object.keys(dedupedNodes).map(k => dedupedNodes[k]).sort((a, b) => b.sampledCallCount - a.sampledCallCount);
// };

// const getTextWidth = function(text, font) {
//   // re-use canvas object for better performance
//   const canvas = getTextWidth.canvas || (getTextWidth.canvas = document.createElement("canvas"));
//   const context = canvas.getContext("2d");
//   context.font = font;
//   const metrics = context.measureText(text);
//   return metrics.width;
// };

class MethodTreeComponent extends Component {
  static workTypeMap = {
    cpu_sample_work: 'cpu-sampling',
  };

  constructor (props) {
    super(props);
    this.state = {
      itemCount: 0,
      req: {url: '', status: 'PENDING', err: ''}
    };
    this.allNodes = {};
    this.curAllNodes = {};
    this.renderData = [];
    this.opened = {}; // keeps track of all opened/closed nodes
    this.highlighted = {}; //keeps track of all highlighted nodes
    this.flatten = this.flatten.bind(this);
    this.stackLineDetailCellRenderer = this.stackLineDetailCellRenderer.bind(this);
    // this.stacklineStatCellRenderer = this.stacklineStatCellRenderer.bind(this);
    //
    // this.toggle = this.toggle.bind(this);
    // this.handleFilterChange = this.handleFilterChange.bind(this);
    // this.highlight = this.highlight.bind(this);
    // this.debouncedHandleFilterChange = debounce(this.handleFilterChange, 250);
    //
    // this.getMaxWidthOfRenderedStacklines = this.getMaxWidthOfRenderedStacklines.bind(this);
    // this.getRenderedDescendantCountForListItem = this.getRenderedDescendantCountForListItem.bind(this);
    // this.getRenderedChildrenCountForListItem = this.getRenderedChildrenCountForListItem.bind(this);
    // this.isNodeHavingChildren = this.isNodeHavingChildren.bind(this);
    //
    // this.dedupeNodes = dedupeNodes(props.allNodes);
    // this.renderData = this.getInitialRenderData(props.location.query[props.filterKey]);
    // this.state.itemCount = this.renderData.length;

    // this.setup(props.containerWidth);
  }
  componentDidMount() {
    const {app, cluster, proc, workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const queryParams = objectToQueryParams({start: profileStart, duration: profileDuration, autoExpand: true});
    const url = `/api/callers/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
    this.setState((prevState) => ({req: {...prevState.req, url: url}}));
    console.log("URL for Post is ", url);
    http.post(url, [])
      .then(resp => {
        this.allNodes[url] = {allList: this.flatten(resp, [], 0), methodLookup: resp['method_lookup']};
        this.curAllNodes = this.allNodes[url];
        this.curAllNodes.allList.forEach((x,i)=> x[LEVEL]===0 && this.renderData.push(x));
        this.setState((prevState) => ({req: {...prevState.req, status: 'SUCCESS'}}));
      })
      .catch(err => {
        this.setState((prevState) => ({req: {...prevState.req, ...{err: err, status: 'ERROR'}}}));
      });
  }

  componentDidUpdate(prevProps) {
    const {app, cluster, proc, workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const {profileStart: prevProfileStart, profileDuration: prevProfileDuration} = prevProps.location.query;
    const queryParams = objectToQueryParams({start: profileStart, duration: profileDuration, autoExpand: true});
    const url = `/api/callers/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
    if(prevProfileStart !== profileStart || prevProfileDuration !== profileDuration || prevProps.traceName !== this.props.traceName){
      this.setState((prevState) => ({req: {...prevState.req, status: 'PENDING', url: url}}));
      http.post(url, [])
        .then(resp => {
          this.allNodes[url] = {allList: this.flatten(resp, [], 0), methodLookup: resp['method_lookup']};
          this.curAllNodes = this.allNodes[url];
          this.curAllNodes.allList.forEach((x,i)=> x[LEVEL]===0 && this.renderData.push(x));
          this.setState((prevState) => ({req: {...prevState.req, status: 'SUCCESS'}}));
        })
        .catch(err => {
          this.setState((prevState) => ({req: {...prevState.req, ...{err: err, status: 'ERROR'}}}));
        });
    }
  }
  // componentWillUpdate(nextProps, nextState) {
  //   console.log("nextProps: ", nextProps);
  //   // this.setup(nextProps.containerWidth);
  // }

  // setup(containerWidth) {
  //   if(containerWidth > 0 && containerWidth !== this.containerWidth) {
  //     this.containerWidth = containerWidth;
  //   }
  // }


  flatten(root, allList, lvl) {
    //sorting logic on Object.entries
    Object.entries(root).forEach(([k, v]) => {
      if (k !== 'method_lookup') {
        const idx = allList.push([parseInt(k), v.data, lvl, 0, false]) - 1; //key, node data, render lvl, subtree node count, IsSubtreeOpened
        if(v.chld) {
          this.flatten(v.chld, allList, lvl + 1);
        }
        allList[idx][3] = allList.length - 1 - idx; //subTree node count
      }
    });
    return allList;
  }

  render () {
    if (!this.state.req.status) return null;

    if (this.state.req.status === 'PENDING') {
      return (
        <div>
          <h4 style={{textAlign: 'center'}}>Please wait, coming right up!</h4>
          <Loader/>
        </div>
      );
    }

    if (this.state.req.status === 'ERROR') {
      return (
        <div>
          <h2 style={{textAlign: 'center'}}>Failed to fetch the data. Please refresh or try again later</h2>
        </div>
      );
    }

    // const filterText = this.props.location.query[this.props.filterKey];
    // const { nextNodesAccessorField } = this.props;
    const containerHeight = window.innerHeight - everythingOnTopHeight; //subtracting height of everything above the container
    const gridHeight = containerHeight - filterBoxHeight; //subtracting height of filter box
    // console.log("cH", containerHeight, " fbh",filterBoxHeight, " gh",gridHeight);
   // console.log("this.allNodes : ",  this.curAllNodes);
    return (
      <div>
        {/*<div style={{flex: "1 1 auto", height: containerHeight + "px"}}>*/}
          {/*<ScrollSync>*/}
            {/*{({ clientHeight, clientWidth, onScroll, scrollHeight, scrollLeft, scrollTop, scrollWidth }) => (*/}
              {/*<div className={styles.GridRow}>*/}
                {/*<div className={styles.LeftGridContainer}>*/}
                  {/*<div className={styles.GridHeader}>*/}
                    {/*<div className={`mdl-textfield mdl-js-textfield ${styles.filterBox}`}>*/}
                      {/*<label htmlFor="method_filter">*/}
                        {/*{nextNodesAccessorField === 'parent' ? "Filter hot methods" : "Filter root callers"}*/}
                      {/*</label>*/}
                      {/*<input*/}
                        {/*className={`mdl-textfield__input`}*/}
                        {/*type="text"*/}
                        {/*defaultValue={filterText}*/}
                        {/*autoFocus*/}
                        {/*onChange={this.debouncedHandleFilterChange}*/}
                        {/*id="method_filter"*/}
                      {/*/>*/}
                    {/*</div>*/}
                  {/*</div>*/}
              <div className={styles.GridBody}>
                <AutoSizer disableHeight>
                {({width}) => (
                  <Grid
                    columnCount={1}
                    columnWidth={width}
                    height={gridHeight}
                    width={width}
                    rowCount={this.renderData.length}
                    rowHeight={stackEntryHeight}
                    cellRenderer={this.stackLineDetailCellRenderer}
                    className={styles.LeftGrid}
                  />
                )}
                </AutoSizer>
              </div>
                {/*<div className={styles.RightGridContainer}>*/}
                  {/*<div className={styles.GridHeader}>*/}
                    {/*<label>Samples</label>*/}
                  {/*</div>*/}
                  {/*<div className={styles.GridBody}>*/}
                    {/*<Grid*/}
                      {/*columnCount={1}*/}
                      {/*columnWidth={rightColumnWidth}*/}
                      {/*height={gridHeight}*/}
                      {/*width={rightColumnWidth}*/}
                      {/*rowCount={this.state.itemCount}*/}
                      {/*rowHeight={stackEntryHeight}*/}
                      {/*cellRenderer={this.stacklineStatCellRenderer}*/}
                      {/*className={styles.RightGrid}*/}
                      {/*overscanRowCount={10}*/}
                      {/*scrollTop={scrollTop}*/}
                      {/*ref={el => this.stacklineStatGrid = el}*/}
                    {/*/>*/}
                  {/*</div>*/}
                {/*</div>*/}
              {/*</div>*/}
            {/*)}*/}
          {/*</ScrollSync>*/}
        {/*</div>*/}
        {/*{!this.state.itemCount && (*/}
          {/*<div style={{flex: "1 1 auto", marginTop: "-" + (gridHeight) + "px"}} className={styles.alert}>No results</div>*/}
        {/*)}*/}
      </div>
    );
  }

  toggle (renderIdx) {
    // console.log('this.renderData inside toggle', this.renderData);
    // console.log('toggle: renderIdx ', renderIdx);
    const allNodesIdx = this.renderData[renderIdx];
    // console.log('allNodesIdx', allNodesIdx);
    let isRenderExpanded = false; //isSubtreeOpened //renderNodeHasItsSubtreeOpened
    if (renderIdx+1 < this.renderData.length && this.renderData[renderIdx+1][LEVEL] > allNodesIdx[LEVEL]) {
      isRenderExpanded = true;
    }

    let isAllNodesExpanded = false;
    if (allNodesIdx+1 < this.curAllNodes.allList.length && this.curAllNodes.allList[allNodesIdx+1][LEVEL] > this.curAllNodes.allList[allNodesIdx][LEVEL]) {
      isAllNodesExpanded = true;
    }
    // const uniqueId = rowData[0];

    // let nodeIndexes;
    // if (this.props.nextNodesAccessorField === 'parent') {
    //   nodeIndexes = rowData[1].parentsWithSampledCallCount;
    // } else {
    //   nodeIndexes = rowData[1].children;
    // }

    // if(!this.opened[uniqueId]) {
    //   //expand
    //   const childRenderData = this.getRenderData(nodeIndexes, null, uniqueId, rowData[3] > 1, rowData[2]);
    //   const postarr = this.renderData.splice(listIdx + 1);
    //   this.renderData = this.renderData.concat(childRenderData, postarr);
    // } else {
    //   //collapse
    //   const descendants = this.getRenderedDescendantCountForListItem(listIdx);
    //   if(descendants > 0) {
    //     this.renderData.splice(listIdx + 1, descendants);
    //   }
    // }
    // this.opened[uniqueId] = !this.opened[uniqueId];
    console.log('idx :', renderIdx);
    console.log('idx :', this.renderData);
    console.log('isRenderExpanded : ',isRenderExpanded);
    if (isRenderExpanded) {
      //collapse
      let idx = renderIdx+1;
      while (idx < this.renderData.length && this.curAllNodes.allList[this.renderData[idx]][LEVEL] > this.curAllNodes.allList[allNodesIdx][LEVEL]) {
        idx++;
      }
      console.log('Collapse: renderData=', this.renderData);
      console.log('idx', idx, 'renderIdx',renderIdx );
      this.renderData.splice(renderIdx+1, idx-renderIdx-1);
      console.log('Collapse: renderData2=', this.renderData);

      this.setState({
        itemCount: this.renderData.length
      });
    } else {
      //expand
      console.log('isAllNodesExpanded : ',isAllNodesExpanded);
      if (isAllNodesExpanded) {
        let idx = allNodesIdx+1;
        while (idx < this.curAllNodes.allList.length && this.curAllNodes.allList[idx][LEVEL] > this.curAllNodes.allList[allNodesIdx][LEVEL]) {
          idx++;
        }
        this.renderData.splice(renderIdx+1, 0, ...Array.from({length: idx-allNodesIdx-1}, (v, i) => i+allNodesIdx+1));
        console.log('renderData after inserting subTree: ', this.renderData);
        this.setState({
          itemCount: this.renderData.length
        });
      } else {
        //disable toggle button if response for network call returns empty json
        console.log('post req with body: ', this.curAllNodes.allList[allNodesIdx][KEY]);
        http.post(this.state.req.url, [this.curAllNodes.allList[allNodesIdx][KEY]])
          .then(resp => {
            this.curAllNodes.methodLookup = [...new Set([...this.curAllNodes.methodLookup, ...resp['method_lookup']])];
            const subTreeList = this.flatten(resp, [], this.curAllNodes.allList[allNodesIdx][LEVEL]);
            console.log('subTreeList: ', subTreeList);
            console.log('renderData pre adding', this.curAllNodes.allList);
            this.curAllNodes.allList.splice(allNodesIdx+1,0,subTreeList);
            console.log('renderData post adding', this.curAllNodes.allList);

            this.setState((prevState) => ({req: {...prevState.req, status: 'SUCCESS'}}));
            console.log('Array inserted', ...Array.from({length: subTreeList.length}, (v, i) => i+allNodesIdx+1));
            console.log('renderData pre adding', this.renderData);

            this.renderData.splice(renderIdx+1, 0, ...Array.from({length: subTreeList.length}, (v, i) => i+allNodesIdx+1));
            console.log('renderData post adding', this.renderData);
            if(subTreeList.length === 0){
              console.log('// TODO: DISABLE TOGGLE BUTTON');
            }
            this.setState({
              itemCount: this.renderData.length
            });
          })
          .catch(err => {
            this.setState((prevState) => ({req: {...prevState.req, ...{err: err, status: 'ERROR'}}}));
            console.log('Show a snackBar to retry toggling later');
          });
      }
    }

  }
  //
  // highlight (path) {
  //   if (path in this.highlighted) {
  //     //highlighted node, remove highlight
  //     delete this.highlighted[path];
  //   } else {
  //     // identifying already highlighted children of path
  //     const highlightedChildren = Object.keys(this.highlighted)
  //       .filter(highlight => highlight.startsWith(path));
  //     if (highlightedChildren.length) {
  //       // delete highlighted children
  //       highlightedChildren.forEach((p) => {
  //         delete this.highlighted[p];
  //       });
  //     }
  //
  //     // identifying already highlighted parents of path, this will always be 1 at max
  //     const highlightedParents = Object.keys(this.highlighted)
  //       .filter(highlight => path.startsWith(highlight));
  //     if (highlightedParents.length) {
  //       // delete highlighted parents
  //       highlightedParents.forEach((p) => {
  //         delete this.highlighted[p];
  //       });
  //     }
  //
  //     this.highlighted[path] = true;
  //   }
  //
  //   if(this.stacklineDetailGrid && this.stacklineStatGrid) {
  //     this.stacklineDetailGrid.forceUpdate();
  //     this.stacklineStatGrid.forceUpdate();
  //   }
  // }
  //
  // handleFilterChange (e) {
  //   const { pathname, query } = this.props.location;
  //   this.props.router.push({ pathname, query: { ...query, [this.props.filterKey]: e.target.value } });
  //   this.renderData = this.getInitialRenderData(e.target.value);
  //   this.setState({
  //     itemCount: this.renderData.length
  //   });
  // }
  //
  stackLineDetailCellRenderer (params) {
    // console.log('params = ', params);

    // console.log('renderData', this.renderData);
    // console.log('renderData rowIndex', this.renderData[params.rowIndex]);
    const rowData = this.curAllNodes.allList[this.renderData[params.rowIndex]];

    console.log('renderData :', this.renderData);
    const [ methodId, lineNo, sampleCount ] = rowData[1];
    const displayNameWithArgs = this.curAllNodes.methodLookup[methodId];
    // const splits = displayNameWithArgs.split(" ");
    // const displayName = splits.length === 2 ? splits[0]: displayNameWithArgs;
    const displayName = displayNameWithArgs;

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
    // const isHighlighted = Object.keys(this.highlighted)
    //   .filter(highlight => highlight.startsWith(uniqueId));
    return (
      <StacklineDetail
        key={params.rowIndex}
        style={{...params.style, height: stackEntryHeight, whiteSpace: 'nowrap'}}
        listIdx={params.rowIndex}
        nodename={displayNameWithArgs}
        stackline={displayName}
        indent={rowData[2]*10}
        nodestate={rowData[4]}
        highlight={false}
        subdued={false}
        onHighlight={()=>console.log('onHighlight')}
        onClick={this.toggle.bind(this, params.rowIndex)}>
      </StacklineDetail>

    );
  }
  //
  // stacklineStatCellRenderer({ columnIndex, key, parent, rowIndex, style }) {
  //   let rowdata = this.renderData[rowIndex];
  //   let n = rowdata[1], uniqueId = rowdata[0];
  //
  //   //TODO: optimize, move below assignment to lifecycle method when properties are received by component
  //   const percentageDenominator = (this.props.allNodes.length > 0) ? this.props.allNodes[0].onStack : 1;
  //   let countToDisplay;
  //
  //   //This condition is equivalent to (n instanceOf HotMethodNode)
  //   //since nextNodesAccessorField is = parent in hot method view and
  //   //Node type for dedupedNodes is HotMethodNode from above
  //   if (this.props.nextNodesAccessorField === 'parent') {
  //     countToDisplay = n.sampledCallCount;
  //   } else {
  //     countToDisplay = n.onStack;
  //   }
  //   const onStackPercentage = Number((countToDisplay * 100) / percentageDenominator).toFixed(2);
  //   const isHighlighted = Object.keys(this.highlighted)
  //     .filter(highlight => highlight.startsWith(uniqueId));
  //
  //   return (
  //     <StacklineStats
  //       key={uniqueId}
  //       style={style}
  //       listIdx={rowIndex}
  //       samples={countToDisplay}
  //       samplesPct={onStackPercentage}
  //       highlight={isHighlighted.length}
  //       subdued={rowdata[3] === 1}>
  //     </StacklineStats>
  //   );
  // }
  //
  // getRenderData (nodeIndexes = [], filterText, parentPath, parentHasSiblings, parentIndent) {
  //   const renderStack = [];
  //   renderStack.push({
  //     p_pth: parentPath, //parent path
  //     gen: {
  //       nis: nodeIndexes, //indexes of first-level nodes in the tree subject to de-duplication
  //       p_ind: parentIndent, //indentation of parent node
  //       p_sib: parentHasSiblings, //parentHasSiblings
  //     }
  //   });
  //   const renderData = [];
  //
  //   while(renderStack.length > 0) {
  //     let se = renderStack.pop();
  //     if (se.gen) {
  //       // only need to de-dupe for bottom-up not top-down,
  //       // hence the ternary :/
  //       const dedupedNodes = this.props.nextNodesAccessorField === 'parent'
  //         ? this.dedupeNodes(se.gen.nis)
  //         : se.gen.nis.map((nodeIndex) => this.props.allNodes[nodeIndex]).slice().sort((a, b) => b.onStack - a.onStack);
  //
  //       //Indent should always be zero if no parent
  //       //Otherwise, if parent has siblings or if this node has siblings, do a major indent of the nodes, minor indent otherwise
  //       const indent = !se.p_pth ? 0 : ((se.gen.p_sib || dedupedNodes.length > 1 ) ? se.gen.p_ind + 10 : se.gen.p_ind + 3);
  //       renderStack.push({
  //         p_pth: se.p_pth,
  //         node: {
  //           dn: dedupedNodes, //first-level nodes
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
  //       let displayName = this.props.methodLookup[n.name][0];
  //       //If this is a first-level node(p_pth will be empty) and filter is applied, skip rendering of node if display name does not match the filter
  //       if(filterText && !se.p_pth && !displayName.match(new RegExp(filterText, 'i'))) {
  //         continue;
  //       }
  //
  //       let uniqueId, newNodeIndexes;
  //       //This condition is equivalent to (n instanceOf HotMethodNode)
  //       //since nextNodesAccessorField is = parent in hot method view and
  //       //Node type for dedupedNodes is HotMethodNode from above
  //       if (this.props.nextNodesAccessorField === 'parent') {
  //         uniqueId = `${se.p_pth}->${n.identifier()}`;
  //         newNodeIndexes = n.parentsWithSampledCallCount;
  //         const lineNoOrNot = (n.belongsToTopLayer)? '' : ':' + n.lineNo;
  //         displayName = displayName + lineNoOrNot;
  //       } else {
  //         // using the index i because in call tree the name of sibling nodes
  //         // can be same, react will throw up, argh!
  //         uniqueId = `${se.p_pth}->${n.name}:${n.lineNo}`;
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
  //           p_pth: uniqueId,
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
  //
  // getInitialRenderData(filterText) {
  //   const { nextNodesAccessorField } = this.props;
  //   let nodeIndexes;
  //   if (nextNodesAccessorField === 'parent') {
  //     nodeIndexes = this.props.nodeIndexes.map((nodeIndex) => [nodeIndex, undefined]);
  //   } else {
  //     nodeIndexes = this.props.nodeIndexes;
  //   }
  //   return this.getRenderData(nodeIndexes, filterText, '', false, 0);
  // }
  //
  // getRenderedDescendantCountForListItem(listIdx) {
  //   let currIdx = listIdx;
  //   let toVisit = this.getRenderedChildrenCountForListItem(currIdx);
  //   while(toVisit > 0) {
  //     toVisit--;
  //     currIdx++;
  //     toVisit += this.getRenderedChildrenCountForListItem(currIdx);
  //   }
  //   return currIdx - listIdx;
  // }
  //
  // getRenderedChildrenCountForListItem(listIdx) {
  //   let children = 0;
  //   let rowdata = this.renderData[listIdx];
  //   if(rowdata) {
  //     const uniqueId = rowdata[0];
  //     if(this.opened[uniqueId]) {
  //       if(this.isNodeHavingChildren(rowdata[1])) {
  //         //At least one rendered child item is going to be present for this item
  //         //Cannot rely on childNodeIndexes(calculated in isNodeHavingChildren method) to get count of children because actual rendered children can be lesser after deduping of nodes for hot method tree
  //         let child_rowdata = this.renderData[listIdx + 1];
  //         if(child_rowdata) {
  //           return child_rowdata[3]; //this is siblings count of child node which implies children count for parent node
  //         } else {
  //           console.error("This should never happen. If list item is expanded and its childNodeIndexes > 0, then at least one more item should be present in renderdata list")
  //         }
  //       }
  //     }
  //   }
  //   return children;
  // }
  //
  // isNodeHavingChildren(node) {
  //   if(this.props.nextNodesAccessorField === 'parent') {
  //     let childNodeIndexes = node.parentsWithSampledCallCount;
  //     if(childNodeIndexes && childNodeIndexes.length > 0) {
  //       if(childNodeIndexes.length !== 1) {
  //         return true;
  //       } else {
  //         //If this has only one childnodeindex and that is "0" node => corresponds to root node which is not rendered in UI
  //         //"if(! node.hasParent()) break;" condition in dedupeNodes method ensure above node is not rendered
  //         return childNodeIndexes[0][0] !== 0;
  //       }
  //     } else {
  //       return false;
  //     }
  //   } else {
  //     return (node.children && node.children.length > 0);
  //   }
  // }
  //
  // getMaxWidthOfRenderedStacklines() {
  //   let maxWidthOfRenderedStacklines = 0;
  //   for(let i = 0;i < this.renderData.length;i++) {
  //     if (maxWidthOfRenderedStacklines < this.renderData[i][4]) {
  //       maxWidthOfRenderedStacklines = this.renderData[i][4];
  //     }
  //   }
  //   maxWidthOfRenderedStacklines += 10; //added some buffer
  //   const minGridWidth = this.containerWidth - rightColumnWidth - 15;
  //   return maxWidthOfRenderedStacklines < minGridWidth ? minGridWidth : maxWidthOfRenderedStacklines;
  // }
}

MethodTreeComponent.propTypes = {
  traceName: React.PropTypes.string
};
// MethodTreeComponent.propTypes = {
//   nodeIndexes: React.PropTypes.array,
//   nextNodesAccessorField: React.PropTypes.string.isRequired,
//   methodLookup: React.PropTypes.array,
//   filterKey: React.PropTypes.string,
//   traceName: React.PropTypes.string
// };

export default withRouter(MethodTreeComponent);
