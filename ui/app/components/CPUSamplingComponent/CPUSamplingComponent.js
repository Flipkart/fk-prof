import React, { Component, PropTypes } from 'react';
import TreeView from 'react-treeview';
import { connect } from 'react-redux';
import { withRouter } from 'react-router';

import fetchCPUSamplingAction from 'actions/CPUSamplingActions';
import safeTraverse from 'utils/safeTraverse';
import memoize from 'utils/memoize';
import debounce from 'utils/debounce';
import Loader from 'components/LoaderComponent';

import styles from './CPUSamplingComponent.css';
import 'react-treeview/react-treeview.css';

let allNodes;
let methodLookup;
const noop = () => {};
const nameFilterFunction = filterText => n => methodLookup[n.name].match(filterText);

const dedupeNodes = (nodes) => {
  let onStackSum = 0;
  let onCPUSum = 0;
  const dedupedNodes = nodes.reduce((prev, curr) => {
    let childOnStack;
    if (Array.isArray(curr)) {
      childOnStack = curr[1];
      curr = allNodes[curr[0]];
    }
    const newPrev = Object.assign({}, prev);
    const newCurr = Object.assign({}, curr);
    // change structure of parent array, store onStack also
    newCurr.parent = [[...curr.parent, newCurr.onStack]];
    // use child's onStack value if available,
    // will be available from penultimate node level
    onStackSum += childOnStack || newCurr.onStack;
    onCPUSum += newCurr.onCPU;
    if (!newPrev[newCurr.name]) {
      newPrev[newCurr.name] = newCurr;
    } else {
      newPrev[newCurr.name].onStack += childOnStack || newCurr.onStack;
      newPrev[newCurr.name].onCPU += newCurr.onCPU;
      newPrev[newCurr.name].parent = [
        ...newPrev[newCurr.name].parent,
        ...newCurr.parent,
      ];
    }
    return newPrev;
  }, {});
  return {
    dedupedNodes: Object.keys(dedupedNodes)
      .map(k => ({ ...dedupedNodes[k] }))
      .sort((a, b) => b.onStack - a.onStack),
    onStackSum,
    onCPUSum,
  };
};
const stringifierFunction = a => Array.isArray(a) ? a[0] : a.name;
const memoizedDedupeNodes = memoize(dedupeNodes, stringifierFunction, true);

export class CPUSamplingComponent extends Component {
  constructor () {
    super();
    this.state = {};
    this.getTree = this.getTree.bind(this);
    this.toggle = this.toggle.bind(this);
    this.handleFilterChange = this.handleFilterChange.bind(this);
    this.debouncedHandleFilterChange = debounce(this.handleFilterChange, 250);
  }

  componentDidMount () {
    const { app, cluster, proc, workType, profileStart } = this.props.location.query;
    const { traceName } = this.props.params;
    this.props.fetchCPUSampling({
      app,
      cluster,
      proc,
      workType,
      traceName,
      query: { start: profileStart },
    });
  }

  componentWillReceiveProps (nextProps) {
    if (nextProps.tree.asyncStatus === 'SUCCESS') {
      allNodes = safeTraverse(nextProps, ['tree', 'data', 'allNodes']);
      methodLookup = safeTraverse(nextProps, ['tree', 'data', 'methodLookup']);
    }
  }

  getTree (nodes = [], pName = '') {
    const { dedupedNodes, onStackSum, onCPUSum } = memoizedDedupeNodes(...nodes);
    return dedupedNodes.map((n) => {
      const uniqueId = pName.toString() + n.name.toString();
      const newNodes = n.parent;
      const displayName = this.props.tree.data.methodLookup[n.name];
      const onStackPercentage = onStackSum && Number((n.onStack * 100) / onStackSum).toFixed(2);
      const onCPUPercentage = onCPUSum && Number((n.onCPU * 100) / onCPUSum).toFixed(2);
      return (
        <TreeView
          itemClassName={`${styles.relative} ${styles.hover}`}
          key={uniqueId}
          defaultCollapsed={!(this.state[uniqueId] && newNodes)}
          nodeLabel={
            <div className={`${styles.listItem}`}>
              <div className={styles.code} title={displayName}>{displayName}</div>
              {pName && (
                <div className={`${styles.pill} ${styles.onStack}`}>
                  {n.onStack} {!!onStackPercentage && (
                    <span style={{ fontSize: 9 }}>({onStackPercentage}%)</span>
                  )}
                </div>
              )}
              <div className={`${styles.pill} ${styles.onCPU}`}>
                {n.onCPU} {!!onCPUPercentage && (
                  <span style={{ fontSize: 9 }}>({onCPUPercentage}%)</span>
                )}
              </div>
            </div>
          }
          onClick={newNodes ? this.toggle.bind(this, newNodes, uniqueId) : noop}
        >
          {
            this.state[uniqueId] && newNodes && this.getTree(newNodes, uniqueId)
          }
        </TreeView>
      );
    });
  }


  toggle (newNodes = [], open) {
    this.setState({ [open]: !this.state[open] });
  }

  handleFilterChange (e) {
    const { pathname, query } = this.props.location;
    this.props.router.push({ pathname, query: { ...query, filterText: e.target.value } });
  }

  render () {
    const { app, cluster, proc, filterText } = this.props.location.query;
    const { traceName } = this.props.params;
    const terminalNodes = safeTraverse(this.props, ['tree', 'data', 'terminalNodes']) || [];
    const filteredTerminalNodes = filterText
      ? terminalNodes.filter(nameFilterFunction(filterText)) : terminalNodes;
    if (this.props.tree.asyncStatus === 'PENDING') {
      return (
        <div>
          <h4 style={{ textAlign: 'center' }}>Please wait, coming right up!</h4>
          <Loader />
        </div>
      );
    }

    if (this.props.tree.asyncStatus === 'ERROR') {
      return (
        <div className={styles.card}>
          <h2>Failed to fetch the data. Please refresh or try again later</h2>
        </div>
      );
    }

    return (
      <div>
        <div className={styles.card} style={{ background: '#C5CAE9' }}>
          <div className="mdl-grid">
            <div className="mdl-cell mdl-cell--3-col">
              <div className={styles.label}>App</div>
              <strong className={styles.bold}>{app}</strong>
            </div>
            <div className="mdl-cell mdl-cell--3-col">
              <div className={styles.label}>Cluster</div>
              <strong className={styles.bold}>{cluster}</strong>
            </div>
            <div className="mdl-cell mdl-cell--3-col">
              <div className={styles.label}>Proc</div>
              <strong className={styles.bold}>{proc}</strong>
            </div>
            <div className="mdl-cell mdl-cell--3-col">
              <div className={styles.label}>Trace Name</div>
              <strong className={styles.bold}>{traceName} (CPU Sampling)</strong>
            </div>
          </div>
        </div>
        <div style={{ padding: '0 10px', margin: '20px 0px' }}>
          <div className={styles.card}>
            <h3 style={{ display: 'flex', alignItems: 'center' }}>
              <span>Hot Methods</span>
              <input
                className={styles.filter}
                type="text"
                placeholder="Type to filter"
                autoFocus
                defaultValue={filterText}
                onChange={this.debouncedHandleFilterChange}
              />
            </h3>
            {!!filteredTerminalNodes.length && (
              <div>
                <div style={{ width: '100%', position: 'relative', height: 20 }}>
                  <div className={`${styles.code} ${styles.heading}`}>Method name</div>
                  <div className={`${styles.onStack} ${styles.heading}`}>On Stack</div>
                  <div className={`${styles.onCPU} ${styles.heading}`}>On CPU</div>
                </div>
                {this.getTree(filteredTerminalNodes)}
              </div>
            )}
            {filterText && !filteredTerminalNodes.length && (
              <p className={styles.alert}>Sorry, no results found for your search query!</p>
            )}
          </div>
        </div>
      </div>
    );
  }
}

CPUSamplingComponent.propTypes = {
  fetchCPUSampling: PropTypes.func,
};

const mapStateToProps = state => ({
  tree: state.cpuSampling || {},
});

const mapDispatchToProps = dispatch => ({
  fetchCPUSampling: params => dispatch(fetchCPUSamplingAction(params)),
});

export default connect(mapStateToProps, mapDispatchToProps)(withRouter(CPUSamplingComponent));
