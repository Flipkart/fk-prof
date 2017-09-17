import React, { Component } from 'react';
import { LRUBasedCache } from '../../utils/cache';
import StacklineDetail from 'components/StacklineDetailComponent';
import StacklineStats from 'components/StacklineStatsComponent';
import { withRouter } from 'react-router';
import { ScrollSync, AutoSizer, Grid } from 'react-virtualized';
import debounce from 'utils/debounce';

import styles from './MethodTreeComponent.css';
import 'react-virtualized/styles.css';
import {objectToQueryParams} from 'utils/UrlUtils';
import CallTreeStore from '../../model/CallTreeStore';
import HotMethodStore from '../../model/HotMethodStore';
import Loader from 'components/LoaderComponent';

const rightColumnWidth = 150;
const everythingOnTopHeight = 270;
const filterBoxHeight = 87;
const stackEntryHeight = 25;
const MAX_TREESTORES_TO_CACHE = 20;

const getTextWidth = function(text, font) {
  // re-use canvas object for better performance
  const canvas = getTextWidth.canvas || (getTextWidth.canvas = document.createElement("canvas"));
  const context = canvas.getContext("2d");
  context.font = font;
  const metrics = context.measureText(text);
  return metrics.width;
};

class MethodTreeComponent extends Component {
  constructor (props) {
    super(props);
    this.asyncStatus = 'PENDING';
    this.containerWidth = 0;
    this.opened = {}; // keeps track of all /closed nodes
    this.highlighted = {}; //keeps track of all highlighted nodes
    this.cachedTreeStores = new LRUBasedCache(MAX_TREESTORES_TO_CACHE);
    this.currTreeStore = null;
    this.renderData = [];
    this.toggleSourceTargetBufferMap = {};

    this.initTreeStore = this.initTreeStore.bind(this);
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
    this.showPromptMsg = this.showPromptMsg.bind(this);
    this.setup(props.containerWidth);
  }

  initTreeStore() {
    const {app, cluster, proc, workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const queryParams = objectToQueryParams({start: profileStart, duration: profileDuration, autoExpand: true});
    const treeType = this.props.nextNodesAccessorField === 'parent' ? 'callees' : 'callers';
    this.url = `/api/${treeType}/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
    this.currTreeStore = this.cachedTreeStores.get(this.url);
    if (this.currTreeStore === null) {
      this.currTreeStore = this.props.nextNodesAccessorField === 'parent' ? new HotMethodStore(this.url) : new CallTreeStore(this.url);   // also add to the treeStore
      this.cachedTreeStores.put(this.url, this.currTreeStore);
      this.opened[this.url] = {};
      this.highlighted[this.url] = {};
      this.toggleSourceTargetBufferMap[this.url] = {};
    }
  }

  componentDidMount() {
    this.initTreeStore();
    this.asyncStatus = 'PENDING';
    const currUrl = this.url;
    this.getRenderData(this.url, this.currTreeStore.getChildrenAsync(-1).catch(this.showPromptMsg), this.props.location.query[this.props.filterKey], -1, false).then(subTreeRenderData => {
      if (currUrl === this.url) {
        this.renderData = subTreeRenderData;
        this.asyncStatus = 'SUCCESS';
        this.forceUpdate(); //Not adviced, but using to avoid a background async completion of a different traceName to update the current state
      }
    });
  }

  componentDidUpdate(prevProps) {
    const {workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const {workType: prevWorkType, selectedWorkType: prevSelectedWorkType, profileStart: prevProfileStart, profileDuration: prevProfileDuration} = prevProps.location.query;

    if (prevWorkType !== workType || prevSelectedWorkType !== selectedWorkType || prevProfileStart !== profileStart || prevProfileDuration !== profileDuration || prevProps.traceName !== this.props.traceName) {
      this.initTreeStore();
      this.asyncStatus = 'PENDING';
      const currUrl = this.url;
      this.getRenderData(this.url, this.currTreeStore.getChildrenAsync(-1).catch(this.showPromptMsg), this.props.location.query[this.props.filterKey], -1, false).then(subTreeRenderData => {
        if (currUrl === this.url) {
          this.renderData = subTreeRenderData;
          this.asyncStatus = 'SUCCESS';
          this.forceUpdate(); //Not adviced, but using to avoid a background async completion of a different traceName to update the current state
        }
      });
    }
  }

  componentWillUpdate(nextProps) {
    this.setup(nextProps.containerWidth);
  }

  setup(containerWidth) {
    if (containerWidth > 0 && containerWidth !== this.containerWidth) {
      this.containerWidth = containerWidth;
    }
  }

  render () {
    if(this.containerWidth === 0) {
      return null;
    }

    const filterText = this.props.location.query[this.props.filterKey];
    const { nextNodesAccessorField } = this.props;
    const containerHeight = window.innerHeight - everythingOnTopHeight; //subtracting height of everything above the container
    const gridHeight = containerHeight - filterBoxHeight; //subtracting height of filter box

    return (
      <div style={{display: "flex", flexDirection: "column", width: this.containerWidth}}>
        <div style={{flex: "1 1 auto", height: containerHeight + "px"}}>
          {this.asyncStatus !== 'PENDING' && <ScrollSync>
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
                          columnWidth={this.getMaxWidthOfRenderedStacklines()}
                          height={gridHeight}
                          width={width}
                          rowCount={this.renderData.length}
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
                      rowCount={this.renderData.length}
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
          }{this.asyncStatus === 'PENDING' &&
          (<div><h4 style={{textAlign: 'center'}}>Please wait, coming right up!</h4>
            <Loader/>
          </div>)}
        </div>
        {this.asyncStatus !== 'PENDING' && !this.renderData.length && !this.props.location.query[this.props.filterKey] && (
          <div style={{flex: "1 1 auto", marginTop: "-" + (gridHeight) + "px"}} className={styles.alert}>There was a problem loading the page, please try later.</div>
        )}
        <div id="policy-submit" className="mdl-js-snackbar mdl-snackbar">
          <div className="mdl-snackbar__text"/>
          <button className="mdl-snackbar__action" type="button"/>
        </div>
      </div>
    );
  }

  toggle(listIdx) {
    const rowData = this.renderData[listIdx];
    const uniqueId = rowData[0];
    if (this.toggleSourceTargetBufferMap[this.url][listIdx]) return;

    //===============Helper functions ================
    const expand = () => {
      this.toggleSourceTargetBufferMap[this.url][listIdx] = listIdx;
      this.opened[this.url][uniqueId] = 1;        //change state of the stackline to loading
      if (this.stacklineDetailGrid) {
        this.stacklineDetailGrid.forceUpdate();     //only stacklineDetail is to updated in order to make the arrow change to loading state
      }
      const currUrl = this.url;
      this.getRenderData(currUrl, this.currTreeStore.getChildrenAsync(uniqueId).catch(this.showPromptMsg), null, rowData[2], rowData[3] > 1).then(subTreeRenderData => {
        const latestTargetIdx = this.toggleSourceTargetBufferMap[currUrl][listIdx];
        this.opened[currUrl][uniqueId] = 2;
        if(listIdx in this.toggleSourceTargetBufferMap[currUrl]) {
          delete this.toggleSourceTargetBufferMap[currUrl][listIdx];
          Object.entries(this.toggleSourceTargetBufferMap[currUrl]).forEach(([k, v]) => {
            this.toggleSourceTargetBufferMap[currUrl][k] = v > latestTargetIdx ? v + subTreeRenderData.length : v;
          });
          if (this.url === currUrl) { //update the render data only if it is the same one on which the toggle getRenderData got initiated and initial render is complete
            this.renderData.splice(latestTargetIdx + 1, 0, ...subTreeRenderData);
            if (this.stacklineDetailGrid && this.stacklineStatGrid) {
              if (subTreeRenderData.length === 0) {
                this.stacklineDetailGrid.forceUpdate();
              }
              this.forceUpdate();
            }
          }
        }
      });
    };

    const collapse = () => {
      this.toggleSourceTargetBufferMap[this.url][listIdx] = listIdx;
      this.opened[this.url][uniqueId] = 1;        //change state of the stackline to loading
      if (this.stacklineDetailGrid) {
        this.stacklineDetailGrid.forceUpdate();     //only stacklineDetail is to updated in order to make the arrow change to loading state
      }
      const latestTargetIdx = this.toggleSourceTargetBufferMap[this.url][listIdx];
      const descendants = this.getRenderedDescendantCountForListItem(latestTargetIdx);
      delete this.toggleSourceTargetBufferMap[this.url][listIdx];
      Object.entries(this.toggleSourceTargetBufferMap[this.url]).forEach(([k, v]) => {
        if(v > latestTargetIdx && v <= latestTargetIdx + descendants){
          delete this.toggleSourceTargetBufferMap[this.url][k];     //need to remove any descendant which has a pending initialized to prevent its result getting appended to the renderList
        } else {
          this.toggleSourceTargetBufferMap[this.url][k] = v > latestTargetIdx ? v - descendants : v;
        }
      });
      this.opened[this.url][uniqueId] = 0;
      if (descendants > 0) {
        this.renderData.splice(latestTargetIdx + 1, descendants);
        if (this.stacklineDetailGrid && this.stacklineStatGrid) {
          this.forceUpdate();
        }
      }
    };
    //================================================
    if (!this.opened[this.url][uniqueId] || this.opened[this.url][uniqueId] === 0) {
      expand();
    } else if (this.opened[this.url][uniqueId] === 2) {
      collapse();
    }
  }

  //highlighted stores the value num of highlighted leaf nodes in subtree rooted at self
  highlight (listIdx) {
    const rowData = this.renderData[listIdx];
    const uniqueId = rowData[0];

    //===============Helper functions ================
    const doHighlight = (uniqueId) => {
      //increment numLeafHighlighted for ancestors, stopping if it is already highlighted and exactly one child (current child) is highlighted or if root is reached
      this.highlighted[this.url][uniqueId] = this.highlighted[this.url][uniqueId] ? this.highlighted[this.url][uniqueId] + 1 : 1;
      if (this.currTreeStore.isRoot(uniqueId)) return;
      const parent = this.currTreeStore.getParent(uniqueId);
      const siblings = this.currTreeStore.getChildren(parent);
      if (!this.highlighted[this.url][this.currTreeStore.getParent(uniqueId)] ||
        (siblings && siblings.reduce((sum, id) => this.highlighted[this.url][id] ? sum + this.highlighted[this.url][id] : sum, 0) !== 1)) {
        doHighlight(this.currTreeStore.getParent(uniqueId));
      }
    };
    const undoHighlight = () => {
      //make zero numLeafHighlighted for each descendant, propagate (reduction of numLeafHighlighted - 1) to its ancestors
      const highlightedChildren = this.currTreeStore.getChildren(uniqueId).filter(id => this.highlighted[this.url][id]);
      if (highlightedChildren.length !== 0) //i.e. is not a highlighted leaf itself
        highlightedChildren.map(id => removeHighlightOfSubTreeRootedAt(id));
      reduceHighlightTillRootStartingAt(uniqueId, highlightedChildren.length === 0 ? 1 : this.highlighted[this.url][uniqueId] - 1)
    };

    const removeHighlightOfSubTreeRootedAt = (uniqueId) => {
      delete this.highlighted[this.url][uniqueId];
      this.currTreeStore.getChildren(uniqueId).filter(id => this.highlighted[this.url][id]).map(id => removeHighlightOfSubTreeRootedAt(id));
    };

    const reduceHighlightTillRootStartingAt = (uniqueId, reduceBy) => {
      if (reduceBy === 0) return;
      this.highlighted[this.url][uniqueId] -= reduceBy;
      if (this.highlighted[this.url][uniqueId] <= 0) delete this.highlighted[this.url][uniqueId];
      if (!this.currTreeStore.isRoot(uniqueId)) {
        reduceHighlightTillRootStartingAt(this.currTreeStore.getParent(uniqueId), reduceBy)
      }
    };
    //================================================

    if (!this.highlighted[this.url][uniqueId]) {
      doHighlight(uniqueId);
    } else {
      undoHighlight();
    }

    if (this.stacklineDetailGrid && this.stacklineStatGrid) {
      this.stacklineDetailGrid.forceUpdate();
      this.stacklineStatGrid.forceUpdate();
    }
  }

  handleFilterChange (e) {
    const { pathname, query } = this.props.location;
    this.props.router.push({ pathname, query: { ...query, [this.props.filterKey]: e.target.value } });
    const currUrl = this.url;
    this.getRenderData(this.url, this.currTreeStore.getChildrenAsync(-1).catch(this.showPromptMsg), e.target.value, -1, false).then(subTreeRenderData => {
      if(currUrl === this.url) {
        this.renderData = subTreeRenderData;
        this.forceUpdate(); //Not adviced, but using to avoid a background async completion of a different traceName to update the current state
      }
    });
  }

  stacklineDetailCellRenderer ({ rowIndex, style }) {
    let rowData = this.renderData[rowIndex];
    let uniqueId = rowData[0];

    const displayName = this.currTreeStore.getMethodName(uniqueId, !(this.props.nextNodesAccessorField === 'parent' && rowData[2] === 0));
    const displayNameWithArgs = this.currTreeStore.getFullyQualifiedMethodName(uniqueId, !(this.props.nextNodesAccessorField === 'parent' && rowData[2] === 0));
    return (
      <StacklineDetail
        key={uniqueId}
        style={{...style, height: stackEntryHeight, whiteSpace: 'nowrap'}}
        listIdx={rowIndex}
        nodename={displayNameWithArgs}
        stackline={displayName}
        indent={rowData[2]}
        nodestate={this.opened[this.url][uniqueId] || 0}
        highlight={this.highlighted[this.url][uniqueId] || 0}
        subdued={rowData[3] === 1}
        onHighlight={this.highlight.bind(this, rowIndex)}
        onClick={this.toggle.bind(this, rowIndex)}>
      </StacklineDetail>

    );
  }

  stacklineStatCellRenderer({ rowIndex, style }) {
    let rowData = this.renderData[rowIndex];
    let uniqueId = rowData[0];
    const percentageDenominator = this.currTreeStore.getChildren(-1).reduce((sum, id) => sum + this.currTreeStore.getSampleCount(id), 0);
    const countToDisplay = this.currTreeStore.getSampleCount(uniqueId);
    const onStackPercentage = Number((countToDisplay * 100) / percentageDenominator).toFixed(2);

    return (
      <StacklineStats
        key={uniqueId}
        style={style}
        listIdx={rowIndex}
        samples={countToDisplay}
        samplesPct={onStackPercentage}
        highlight={this.highlighted[this.url][uniqueId]}
        subdued={rowData[3] === 1}>
      </StacklineStats>
    );
  }

  getRenderData(currUrl, asyncIds, filterText, parentIndent, parentHasSiblings) {
    return new Promise(resolve => {
      asyncIds.then(ids => {
        if (ids) {
          ids.sort((a, b) => this.cachedTreeStores.get(currUrl).getSampleCount(b) - this.cachedTreeStores.get(currUrl).getSampleCount(a));
          const asyncIdsRenderData = ids.map((id) => new Promise(resolve => {
            const indent = parentIndent === -1 ? 0 : ((parentHasSiblings || ids.length > 1 ) ? parentIndent + 10 : parentIndent + 4);
            const displayName = this.cachedTreeStores.get(currUrl).getMethodName(id, !(this.props.nextNodesAccessorField === 'parent' && indent === 0));
            if (filterText && indent === 0 && !displayName.match(new RegExp(filterText, 'i'))) {
              resolve([]);
              return;
            }
            const stackEntryWidth = getTextWidth(displayName, "14px Arial") + 28 + indent; //28 is space taken up by icons
            let renderDataList = [[id, null, indent, ids.length, stackEntryWidth]];
            if (ids.length === 1 || this.opened[currUrl][id] === 2) {
              //change state of the stackline to loading (not required for current implementation because the current stackline is still not yet rendered)
              this.opened[currUrl][id] = 1;
              this.getRenderData(currUrl, this.cachedTreeStores.get(currUrl).getChildrenAsync(id).catch(this.showPromptMsg), filterText, indent, ids.length > 1).then(subTreeRenderDataList => {
                this.opened[currUrl][id] = 2;
                resolve(renderDataList.concat(subTreeRenderDataList));
              });
            } else {
              resolve(renderDataList);
            }
          }));
          Promise.all(asyncIdsRenderData).then(idsRenderData => {
            resolve(idsRenderData.reduce((accumRenderData, idRenderData) => accumRenderData.concat(idRenderData), []));
          })
        } else {
          resolve([]);
        }
      });
    });
  }

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
    let rowData = this.renderData[listIdx];
    if(rowData) {
      const uniqueId = rowData[0];
      if (this.opened[this.url][uniqueId] === 1 || this.opened[this.url][uniqueId] === 2) {
        const nodeChildren = this.currTreeStore.getChildren(uniqueId);
        if (nodeChildren.length > 0) {
          let nextRowData = this.renderData[listIdx + 1];
          console.log(nodeChildren, nextRowData[0]);
          if(nextRowData && nodeChildren.includes(nextRowData[0])) {
            return nextRowData[3]; //this is siblings count of child node which implies children count for parent node
          }
        }
      }
    }
    return 0;
  }

  isNodeHavingChildren(uniqueId) {
    return (this.currTreeStore.getChildren(uniqueId).length > 0);
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

  showPromptMsg = (msg) => {
    componentHandler.upgradeDom(); // eslint-disable-line  //To apply mdl JS behaviours on components loaded later https://github.com/google/material-design-lite/issues/5081
    document.querySelector('#policy-submit').MaterialSnackbar.showSnackbar({message: msg, timeout: 3500});
    return [];
  };
}

MethodTreeComponent.propTypes = {
  allNodes: React.PropTypes.array,
  nextNodesAccessorField: React.PropTypes.string.isRequired,
  filterKey: React.PropTypes.string
};

export default withRouter(MethodTreeComponent);
