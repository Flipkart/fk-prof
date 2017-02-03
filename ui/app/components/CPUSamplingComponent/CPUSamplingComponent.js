import React, { Component, PropTypes } from 'react';
import TreeView from 'react-treeview';
import { connect } from 'react-redux';
import { withRouter } from 'react-router';

import fetchCPUSamplingAction from 'actions/CPUSamplingActions';
import safeTraverse from 'utils/safeTraverse';
import memoize from 'utils/memoize';

import styles from './CPUSamplingComponent.css';
import 'react-treeview/react-treeview.css';

const noop = () => {};

const dedupeNodes = (nodes) => {
  const dedupedNodes = nodes.reduce((prev, curr) => {
    const newPrev = Object.assign({}, prev);
    const newCurr = Object.assign({}, curr);
    if (!newPrev[newCurr.name]) {
      newPrev[newCurr.name] = newCurr;
    } else {
      newPrev[newCurr.name].onStack += newCurr.onStack;
      newPrev[newCurr.name].onCPU += newCurr.onCPU;
      newPrev[newCurr.name].parent = [...newPrev[newCurr.name].parent, ...newCurr.parent];
    }
    return newPrev;
  }, {});
  return Object.keys(dedupedNodes)
    .map(k => ({ ...dedupedNodes[k], deduped: true }))
    .sort((a, b) => b.onCPU - a.onCPU);
};

const memoizedDedupeNodes = memoize(dedupeNodes, a => a.name, true);

export class CPUSamplingComponent extends Component {
  constructor () {
    super();
    this.state = {};
    this.getTree = this.getTree.bind(this);
    this.toggle = this.toggle.bind(this);
    this.handleFilterChange = this.handleFilterChange.bind(this);
  }

  componentDidMount () {
    const { app, cluster, proc } = this.props.location.query;
    const { traceName } = this.props.params;
    this.props.fetchCPUSampling({ app, cluster, proc, workType: 'cpu-sampling', traceName });
  }

  getTree (nodes = [], pName = '') {
    const dedupedNodes = memoizedDedupeNodes(...nodes);
    return dedupedNodes.map((n, i) => {
      const uniqueId = i + pName.toString() + n.name.toString();
      const newNodes = n.parent;
      return (
        <TreeView
          key={uniqueId}
          defaultCollapsed
          nodeLabel={
            <span className={styles.listItem}>
              <span className={styles.code}>{n.name}</span>
              <span className={styles.pill}>On CPU: {n.onCPU}</span>
            </span>
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
    this.setState({
      ...this.state,
      [open]: !this.state[open],
    });
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
      ? terminalNodes.filter(n => n.name.match(filterText)) : terminalNodes;
    if (this.props.tree.asyncStatus === 'PENDING') {
      return (
        <h2>Please wait, coming right up!</h2>
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
                value={filterText}
                onChange={this.handleFilterChange}
              />
            </h3>
            {this.getTree(filteredTerminalNodes)}
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
