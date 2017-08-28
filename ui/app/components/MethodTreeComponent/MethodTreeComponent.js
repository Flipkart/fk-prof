import React, { Component } from 'react';
import StacklineDetail from 'components/StacklineDetailComponent';
import StacklineStats from 'components/StacklineStatsComponent';
import { withRouter } from 'react-router';
import { ScrollSync, AutoSizer, Grid } from 'react-virtualized';
import debounce from 'utils/debounce';

import styles from './MethodTreeComponent.css';
import HotMethodNode from '../../pojos/HotMethodNode';
import 'react-virtualized/styles.css';
import {objectToQueryParams} from 'utils/UrlUtils';
import CallTreeStore from '../../model/CallTreeStore';
import HotMethodStore from '../../model/HotMethodStore';
import Loader from 'components/LoaderComponent';

const rightColumnWidth = 150;
const everythingOnTopHeight = 270;
const filterBoxHeight = 87;
const stackEntryHeight = 25;

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
    this.state = {
      itemCount: 0,
      req: {url: '', status: 'PENDING', err: ''}
    };

    this.containerWidth = 0;
    this.opened = {}; // keeps track of all opened/closed nodes
    this.highlighted = {}; //keeps track of all highlighted nodes

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

    this.renderData = [];
    this.state.itemCount = this.renderData.length;

    this.setup(props.containerWidth);
  }

  initTreeStore() {
    const {app, cluster, proc, workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const queryParams = objectToQueryParams({start: profileStart, duration: profileDuration, autoExpand: true});
    if (this.props.nextNodesAccessorField === 'parent') {
      const url = `/api/callees/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
      this.treeStore = new HotMethodStore(url);
    } else {
      const url = `/api/callers/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
      this.treeStore = new CallTreeStore(url);
    }
  }

  componentDidMount() {
    this.initTreeStore();
    this.getRenderData(this.treeStore.getChildrenAsync(-1), this.props.filterKey, -1, false).then(subTreeRenderData => {
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
    if(prevProfileStart !== profileStart || prevProfileDuration !== profileDuration || prevProps.traceName !== this.props.traceName){
      this.getRenderData(this.treeStore.getChildrenAsync(-1), this.props.filterKey, -1, false).then(subTreeRenderData => {
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
      <div style={{display: "flex", flexDirection: "column", width: this.containerWidth}}>
        <div style={{flex: "1 1 auto", height: containerHeight + "px"}}>
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
                          columnWidth={this.getMaxWidthOfRenderedStacklines()}
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

  toggle(listIdx) {
    //TODO: Pick a lock for the listIdx, i.e. if any toggle is pending on same uniqueId show a snackBar
    const rowData = this.renderData[listIdx];
    const uniqueId = rowData[0];
    if (!this.opened[uniqueId]) {
      //expand
      this.getRenderData(this.treeStore.getChildrenAsync(uniqueId), null, rowData[2], rowData[3] > 1).then(subTreeRenderData => {
        this.renderData.splice(listIdx + 1, 0, ...subTreeRenderData);
        this.opened[uniqueId] = true;
        if (subTreeRenderData.length === 0) {
          this.stacklineDetailGrid.forceUpdate();
        }
        this.setState({
          itemCount: this.renderData.length
        });
      });
    } else {
      //collapse
      const descendants = this.getRenderedDescendantCountForListItem(listIdx);
      if (descendants > 0) {
        this.renderData.splice(listIdx + 1, descendants);
        this.opened[uniqueId] = false;
        this.setState({
          itemCount: this.renderData.length
        });
      }
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
    this.getRenderData(this.treeStore.getChildrenAsync(-1), e.target.value, -1, false).then(subTreeRenderData => {
      this.renderData = subTreeRenderData;
      this.setState({
        itemCount: this.renderData.length
      });
    });
  }

  stacklineDetailCellRenderer ({ rowIndex, style }) {
    let rowData = this.renderData[rowIndex];
    let uniqueId = rowData[0];

    const displayName = this.treeStore.getNameWithArgs(uniqueId);

    const isHighlighted = Object.keys(this.highlighted)
      .filter(highlight => highlight.startsWith(uniqueId));
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
    const percentageDenominator = this.treeStore.getChildren(-1).reduce((sum, id) => sum + this.treeStore.getSampleCount(id), 0);
    const countToDisplay = this.treeStore.getSampleCount(uniqueId);
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
        if (ids) {
          ids.sort((a, b) => this.treeStore.getSampleCount(b) - this.treeStore.getSampleCount(a));
          const asyncIdsRenderData = ids.map((id) => new Promise(resolve => {
            const indent = parentIndent === -1 ? 0 : ((parentHasSiblings || ids.length > 1 ) ? parentIndent + 10 : parentIndent + 4);
            const displayName = this.treeStore.getNameWithArgs(id);
            const stackEntryWidth = getTextWidth(displayName, "14px Arial") + 28 + indent; //28 is space taken up by icons
            let renderDataList = [[id, null, indent, ids.length, stackEntryWidth]];
            if (filterText && indent === -1 && !displayName.match(new RegExp(filterText, 'i'))) {
              renderDataList = [];
            }
            if (ids.length === 1 || this.opened[id]) {
              this.opened[id] = true;
              this.getRenderData(this.treeStore.getChildrenAsync(id), filterText, indent, ids.length > 1).then(subTreeRenderDataList => {
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
      if(this.opened[uniqueId]) {
        if(this.isNodeHavingChildren(uniqueId)) {
          //At least one rendered child item is going to be present for this item
          //Cannot rely on childNodeIndexes(calculated in isNodeHavingChildren method) to get count of children because actual rendered children can be lesser after deduping of nodes for hot method tree
          let child_rowdata = this.renderData[listIdx + 1];
          if(child_rowdata) {
            return child_rowdata[3]; //this is siblings count of child node which implies children count for parent node
          } else {
            console.error("This should never happen. If list item is expanded and its childNodeIndexes > 0, then at least one more item should be present in renderData list")
          }
        }
      }
    }
    return 0;
  }

  isNodeHavingChildren(uniqueId) {
    return (this.treeStore.getChildren(uniqueId).length > 0);
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
