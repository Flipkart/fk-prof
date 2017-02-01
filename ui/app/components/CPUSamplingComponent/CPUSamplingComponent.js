import React, { Component, PropTypes } from 'react';
import TreeView from 'react-treeview';
import { connect } from 'react-redux';
import { withRouter } from 'react-router';

import fetchCPUSamplingAction from 'actions/CPUSamplingActions';
import safeTraverse from 'utils/safeTraverse';

import styles from './CPUSamplingComponent.css';
import 'react-treeview/react-treeview.css';

const noop = () => {};

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

  getTree (nodes = []) {
    return nodes.map((n) => {
      const newNodes = n.members || n.parent;
      return (
        <TreeView
          defaultCollapsed
          nodeLabel={
            <span className={styles.listItem}>
              <span className={styles.code}>{n.name}</span>
              <span className={styles.pill}>On CPU: {n.onCPU}</span>
            </span>
          }
          onClick={newNodes ? this.toggle.bind(this, newNodes, n.name) : noop}
        >
          {
            this.state[n.name] && newNodes && this.getTree(newNodes)
          }
        </TreeView>
      );
    });
  }

  toggle (newNodes = [], open) {
    this.setState({
      ...this.state,
      [open]: true,
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
      ? terminalNodes.filter(n => n.name.indexOf(filterText) > -1) : terminalNodes;

    if (this.props.tree.asyncStatus === 'PENDING') {
      return (
        <div className="mdl-progress mdl-js-progress mdl-progress__indeterminate" />
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
