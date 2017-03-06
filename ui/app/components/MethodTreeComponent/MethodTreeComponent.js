import React, { Component } from 'react';
import TreeView from 'react-treeview';
import { withRouter } from 'react-router';
import memoize from 'utils/memoize';
import debounce from 'utils/debounce';

import 'react-treeview/react-treeview.css';
import styles from './MethodTreeComponent.css';

const noop = () => {};
const filterPaths = (pathSubset, k) => k.indexOf(pathSubset) === 0;

const getNode = allNodes => node => Number.isInteger(node) ? allNodes[node] : node;

const dedupeNodes = allNodes => nextNodesAccessorField => (nodes) => {
  let globalOnCPUSum = 0;
  const dedupedNodes = nodes.reduce((prev, curr) => {
    let childOnStack;
    if (Array.isArray(curr)) {
      childOnStack = curr[1];
      curr = allNodes[curr[0]];
    } else if (Number.isInteger(curr)) {
      curr = allNodes[curr];
    } else {
      // will be executed only for top level nodes
      // store the global onCPU count
      globalOnCPUSum += curr.onCPU;
    }
    const newPrev = Object.assign({}, prev);
    const newCurr = Object.assign({}, curr);
    const evaluatedOnStack = childOnStack || newCurr.onStack;
    newCurr.onStack = evaluatedOnStack;
    // only do this if it's bottom-up or nextNodesAccessorField === 'parent'
    // change structure of parent array, store onStack also
    if (nextNodesAccessorField === 'parent') {
      newCurr[nextNodesAccessorField] = newCurr.name
        ? [[...curr[nextNodesAccessorField], evaluatedOnStack]] : [];
    } else {
      // children case, as children [] might not be present
      newCurr[nextNodesAccessorField] = newCurr[nextNodesAccessorField] || [];
    }
    // use child's onStack value if available,
    // will be available from penultimate node level

    if (!newPrev[newCurr.name]) {
      newPrev[newCurr.name] = newCurr;
    } else {
      newPrev[newCurr.name].onStack += evaluatedOnStack;
      newPrev[newCurr.name].onCPU += newCurr.onCPU;
      newPrev[newCurr.name][nextNodesAccessorField] = [
        ...newPrev[newCurr.name][nextNodesAccessorField],
        ...newCurr[nextNodesAccessorField],
      ];
    }
    return newPrev;
  }, {});
  return {
    dedupedNodes: Object.keys(dedupedNodes)
      .map(k => ({ ...dedupedNodes[k] }))
      .sort((a, b) => b.onStack - a.onStack),
    globalOnCPUSum,
  };
};

const stringifierFunction = function (a) {
  if (Array.isArray(a)) return a[0];
  if (Number.isInteger(a)) return a;
  return a.name;
};


class MethodTreeComponent extends Component {
  constructor (props) {
    super(props);
    this.state = {
      opened: {}, // keeps track of all opened/closed nodes
      highlighted: {}, // keeps track of all highlighted nodes
    };
    this.getTree = this.getTree.bind(this);
    this.toggle = this.toggle.bind(this);
    this.handleFilterChange = this.handleFilterChange.bind(this);
    this.highlight = this.highlight.bind(this);
    this.debouncedHandleFilterChange = debounce(this.handleFilterChange, 250);
    this.memoizedDedupeNodes = memoize(
      dedupeNodes(props.allNodes)(props.nextNodesAccessorField), stringifierFunction, true,
    );
  }

  getTree = percentageDenominator => (nodes = [], pName = '', filterText) => {
    // only need to de-dupe for bottom-up not top-down,
    // hence the ternary :/
    const { dedupedNodes, globalOnCPUSum } = this.props.nextNodesAccessorField === 'parent'
      ? this.memoizedDedupeNodes(...nodes)
      : { dedupedNodes: nodes.map(getNode(this.props.allNodes)) };
    
    // for call tree, it'll be passed from the parent
    // and for bottom-up we'll use the top level globalOnCPUSum,
    // and pass it around till the leaf level
    percentageDenominator = percentageDenominator || globalOnCPUSum;
    const dedupedTreeNodes = dedupedNodes.map((n, i) => {
      // using the index because in call tree the name of sibling nodes
      // can be same, react will throw up, argh!
      const uniqueId = `${this.props.nextNodesAccessorField !== 'parent' ? i : ''}${pName.toString()}->${n.name.toString()}`;
      const newNodes = n[this.props.nextNodesAccessorField];
      const displayName = this.props.methodLookup[n.name];
      const onStackPercentage = Number((n.onStack * 100) / percentageDenominator).toFixed(2);
      const onCPUPercentage = Number((n.onCPU * 100) / percentageDenominator).toFixed(2);
      const showDottedLine = pName && dedupedNodes.length >= 2 && dedupedNodes.length !== i + 1 &&
        this.state.opened[uniqueId];
      const isHighlighted = Object.keys(this.state.highlighted)
        .filter(filterPaths.bind(null, uniqueId));
      return (
        <TreeView
          data-nodename={displayName}
          itemClassName={`${styles.relative} ${styles.hover} ${showDottedLine ? 'dotted-line' : ''}`}
          key={uniqueId}
          defaultCollapsed={!(this.state.opened[uniqueId] && newNodes)}
          nodeLabel={
            <div className={styles.listItem}>
              <div
                className={`${styles.code} ${isHighlighted.length && styles.yellow}`}
                title={displayName}
                onClick={this.highlight.bind(this, uniqueId)}
              >
                {displayName}
              </div>
              {!!n.onCPU && (
                <div className={`${styles.pill} ${styles.onCPU}`}>
                  <div className={styles.number}>{n.onCPU}</div>
                  <div className={styles.percentage}>
                    <div className={styles.shade} style={{ width: `${onCPUPercentage}%` }} />
                    {onCPUPercentage}%
                  </div>
                </div>
              )}
              {(this.props.nextNodesAccessorField !== 'parent' || pName) && (
                <div className={`${styles.pill} ${styles.onStack}`}>
                  <div className={styles.number}>{n.onStack}</div>
                  <div className={styles.percentage}>
                    <div className={styles.shade} style={{ width: `${onStackPercentage}%` }} />
                    {onStackPercentage}%
                  </div>
                </div>
              )}
            </div>
          }
          onClick={newNodes ? this.toggle.bind(this, uniqueId) : noop}
        >
          {
            this.state.opened[uniqueId] && newNodes && this.getTree(percentageDenominator)(newNodes, uniqueId)
          }
        </TreeView>
      );
    });
    return filterText
      ? dedupedTreeNodes.filter(node => node.props['data-nodename'].match(new RegExp(filterText, 'i')))
      : dedupedTreeNodes;
  }

  highlight (path) {
    if (path in this.state.highlighted) {
      const state = Object.assign({}, this.state);
      delete state.highlighted[path];
      this.setState(state);
      return;
    }
    // so no exact path matches
    // what if click was on a parent node
    const partialMatchedPaths = Object.keys(this.state.highlighted)
      .filter(filterPaths.bind(null, path));

    if (partialMatchedPaths.length) {
      // delete the partial matches from state,
      // so that new tree would get highlighted
      let state = Object.assign({}, this.state);
      partialMatchedPaths.forEach((p) => {
        delete state.highlighted[p];
      });
      this.setState(state);
    }

    // all good, highlight!
    this.setState({
      highlighted: {
        ...this.state.highlighted,
        [path]: true,
      },
    });
  }

  toggle (open) {
    this.setState({
      opened: {
        ...this.state.opened,
        [open]: !this.state.opened[open],
      },
    });
  }

  handleFilterChange (e) {
    const { pathname, query } = this.props.location;
    this.props.router.push({ pathname, query: { ...query, filterText: e.target.value } });
  }

  render () {
    const { filterText } = this.props.location.query;
    const { nextNodesAccessorField } = this.props;
    const treeNodes = this.getTree(this.props.percentageDenominator)(this.props.nodes, '', filterText);
    return (
      <div style={{ padding: '0 10px', margin: '20px 0px' }}>
        <div>
          <div>
            <div style={{ width: '100%', position: 'relative', height: 30 }}>
              <div className={`${styles.code} ${styles.heading}`}>
                Method name
                <input
                  className={styles.filter}
                  type="text"
                  placeholder="Type to filter"
                  autoFocus
                  defaultValue={filterText}
                  onChange={this.debouncedHandleFilterChange}
                />
              </div>
              <div className={`${styles.onCPU} ${styles.heading}`}>On CPU</div>
              <div className={`${styles.onStack} ${styles.heading}`}>On Stack</div>
            </div>
            {treeNodes}
          </div>
          {filterText && !treeNodes.length && (
            <p className={styles.alert}>Sorry, no results found for your search query!</p>
          )}
        </div>
      </div>
    );
  }
}

MethodTreeComponent.propTypes = {
  allNodes: React.PropTypes.array,
  nodes: React.PropTypes.array,
  nextNodesAccessorField: React.PropTypes.string.isRequired,
  methodLookup: React.PropTypes.array,
};

export default withRouter(MethodTreeComponent);
