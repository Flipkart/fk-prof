import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';

import fetchCPUSamplingAction from 'actions/AggregatedProfileDataActions';
import safeTraverse from 'utils/safeTraverse';
import Loader from 'components/LoaderComponent';
import MethodTree from 'components/MethodTreeComponent';
import Tabs from 'components/Tabs';
import styles from './CPUSamplingComponent.css';


export class CPUSamplingComponent extends Component {
  componentDidMount () {
    const { app, cluster, proc, workType, profileStart, selectedWorkType } = this.props.location.query;
    const { traceName } = this.props.params;
    this.props.fetchCPUSampling({
      app,
      cluster,
      proc,
      workType,
      selectedWorkType,
      traceName,
      query: { start: profileStart },
    });
  }

  render () {
    const { app, cluster, proc } = this.props.location.query;
    const { traceName } = this.props.params;

    if (!this.props.tree.asyncStatus) return null;

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
        <Tabs>
          <div>
            <div>Hot Methods</div>
            <div>
              <MethodTree
                allNodes={safeTraverse(this.props, ['tree', 'data', 'allNodes'])}
                nodes={safeTraverse(this.props, ['tree', 'data', 'terminalNodes'])}
                nextNodesAccessorField="parent"
                methodLookup={safeTraverse(this.props, ['tree', 'data', 'methodLookup'])}
              />
            </div>
          </div>
          <div>
            <div>Call Tree</div>
            <div>
              <MethodTree
                allNodes={safeTraverse(this.props, ['tree', 'data', 'allNodes'])}
                nodes={safeTraverse(this.props, ['tree', 'data', 'treeRoot', 'children'])}
                nextNodesAccessorField="children"
                methodLookup={safeTraverse(this.props, ['tree', 'data', 'methodLookup'])}
              />
            </div>
          </div>
        </Tabs>
      </div>
    );
  }
}

CPUSamplingComponent.propTypes = {
  fetchCPUSampling: PropTypes.func,
  params: PropTypes.shape({
    traceName: PropTypes.string.isRequired,
  }),
  tree: PropTypes.shape({
    asyncStatus: PropTypes.string,
    data: PropTypes.shape({
      allNodes: PropTypes.array,
      methodLookup: PropTypes.array,
      terminalNodes: PropTypes.array,
    }),
  }),
  location: PropTypes.shape({
    query: PropTypes.shape({
      app: PropTypes.string,
      cluster: PropTypes.string,
      proc: PropTypes.string,
      workType: PropTypes.string,
      profileStart: PropTypes.string,
    }),
  }),
};

const mapStateToProps = state => ({
  tree: state.aggregatedProfileData || {},
});

const mapDispatchToProps = dispatch => ({
  fetchCPUSampling: params => dispatch(fetchCPUSamplingAction(params)),
});

export default connect(mapStateToProps, mapDispatchToProps)(CPUSamplingComponent);
