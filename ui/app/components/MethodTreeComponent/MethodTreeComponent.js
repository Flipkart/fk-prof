import React, { Component } from 'react';
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
      req: {url: '', status: 'PENDING', err: ''},
      asyncStatus: 'PENDING'
    };

    this.containerWidth = 0;
    this.opened = {}; // keeps track of all /closed nodes
    this.highlighted = {}; //keeps track of all highlighted nodes
    this.treeStore = {};
    this.renderData = [];
    this.state.itemCount = this.renderData.length;


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
    const treeType = this.props.nextNodesAccessorField === 'parent'? 'callees': 'callers';
    this.url = `/api/${treeType}/${app}/${cluster}/${proc}/${MethodTreeComponent.workTypeMap[workType || selectedWorkType]}/${this.props.traceName}` + ((queryParams) ? '?' + queryParams : '');
    if(!this.treeStore[this.url]) {
      this.treeStore[this.url] = this.props.nextNodesAccessorField === 'parent' ? new HotMethodStore(this.url) : new CallTreeStore(this.url);
      this.opened[this.url] = {};
    }
  }

  componentDidMount() {
    this.initTreeStore();
    this.setState({asyncStatus: 'PENDING'});
    this.getRenderData(this.treeStore[this.url].getChildrenAsync(-1).catch(this.showPromptMsg), this.props.location.query[this.props.filterKey], -1, false).then(subTreeRenderData => {
      this.renderData = subTreeRenderData;
      this.setState({
        itemCount: this.renderData.length,
        asyncStatus: 'SUCCESS'
      });
    });
  }

  componentDidUpdate(prevProps) {
    const {workType, selectedWorkType, profileStart, profileDuration} = this.props.location.query;
    const {workType: prevWorkType, selectedWorkType: prevSelectedWorkType, profileStart: prevProfileStart, profileDuration: prevProfileDuration} = prevProps.location.query;
    if(prevWorkType !== workType || prevSelectedWorkType !== selectedWorkType || prevProfileStart !== profileStart || prevProfileDuration !== profileDuration || prevProps.traceName !== this.props.traceName){
      this.initTreeStore();
      this.setState({asyncStatus: 'PENDING'});
      this.getRenderData(this.treeStore[this.url].getChildrenAsync(-1).catch(this.showPromptMsg), this.props.location.query[this.props.filterKey], -1, false).then(subTreeRenderData => {
        this.renderData = subTreeRenderData;
        this.setState({
          itemCount: this.renderData.length,
          asyncStatus: 'SUCCESS'
        });
        if(this.stacklineDetailGrid && this.stacklineStatGrid) {
          this.stacklineDetailGrid.forceUpdate();
          this.stacklineStatGrid.forceUpdate();
        }
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
          {this.state.asyncStatus !== 'PENDING' && <ScrollSync>
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
          }{this.state.asyncStatus === 'PENDING' &&
          (<div><h4 style={{textAlign: 'center'}}>Please wait, coming right up!</h4>
            <Loader/>
          </div>)}
        </div>
        {this.state.asyncStatus !== 'PENDING' && !this.state.itemCount && (
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
    //TODO: Pick a lock for the listIdx, i.e. if any toggle is pending on same uniqueId show a snackBar
    const rowData = this.renderData[listIdx];
    const uniqueId = rowData[0];
    if (!this.opened[this.url][uniqueId]) {
      //expand
      this.getRenderData(this.treeStore[this.url].getChildrenAsync(uniqueId).catch(this.showPromptMsg), null, rowData[2], rowData[3] > 1).then(subTreeRenderData => {
        this.renderData.splice(listIdx + 1, 0, ...subTreeRenderData);
        this.opened[this.url][uniqueId] = true;
        if (subTreeRenderData.length === 0 && this.stacklineDetailGrid) {
          this.stacklineDetailGrid.forceUpdate();     //only stacklineDetail is to updated in order to make the arrow downwards
        }
        this.setState({
          itemCount: this.renderData.length,
          asyncStatus: 'SUCCESS'
        });
      });
    } else {
      //collapse
      const descendants = this.getRenderedDescendantCountForListItem(listIdx);
      if (descendants > 0) {
        this.renderData.splice(listIdx + 1, descendants);
        this.opened[this.url][uniqueId] = false;
        this.setState({
          itemCount: this.renderData.length,
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
    this.getRenderData(this.treeStore[this.url].getChildrenAsync(-1).catch(this.showPromptMsg), e.target.value, -1, false).then(subTreeRenderData => {
      this.renderData = subTreeRenderData;
      this.setState({
        itemCount: this.renderData.length
      });
    });
  }

  stacklineDetailCellRenderer ({ rowIndex, style }) {
    let rowData = this.renderData[rowIndex];
    let uniqueId = rowData[0];

    const displayName =  this.treeStore[this.url].getName(uniqueId, !(this.props.nextNodesAccessorField === 'parent' && rowData[2] === 0));

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
        nodestate={this.opened[this.url][uniqueId]}
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
    const percentageDenominator = this.treeStore[this.url].getChildren(-1).reduce((sum, id) => sum + this.treeStore[this.url].getSampleCount(id), 0);
    const countToDisplay = this.treeStore[this.url].getSampleCount(uniqueId);
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
          ids.sort((a, b) => this.treeStore[this.url].getSampleCount(b) - this.treeStore[this.url].getSampleCount(a));
          const asyncIdsRenderData = ids.map((id) => new Promise(resolve => {
            const indent = parentIndent === -1 ? 0 : ((parentHasSiblings || ids.length > 1 ) ? parentIndent + 10 : parentIndent + 4);
            const displayName = this.treeStore[this.url].getName(id, !(this.props.nextNodesAccessorField === 'parent' && indent === 0));
            const stackEntryWidth = getTextWidth(displayName, "14px Arial") + 28 + indent; //28 is space taken up by icons
            let renderDataList = [[id, null, indent, ids.length, stackEntryWidth]];
            if (filterText && indent === 0 && !displayName.match(new RegExp(filterText, 'i'))) {
              renderDataList = [];
            }
            if (ids.length === 1 || this.opened[this.url][id]) {
              this.opened[this.url][id] = true;
              this.getRenderData(this.treeStore[this.url].getChildrenAsync(id).catch(this.showPromptMsg), filterText, indent, ids.length > 1).then(subTreeRenderDataList => {
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
      if(this.opened[this.url][uniqueId]) {
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
    return (this.treeStore[this.url].getChildren(uniqueId).length > 0);
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
