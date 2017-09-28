import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import Select from 'react-select';

import fetchClustersAction from 'actions/ClusterActions';
import safeTraverse from 'utils/safeTraverse';

import styles from './ClusterSelectComponent.css';

const noop = () => {};

class ClusterSelectComponent extends Component {
  componentDidMount () {
    const { app } = this.props;
    if (app) {
      this.props.getClusters({ app });
    }
  }

  componentWillReceiveProps (nextProps) {
    if (nextProps.app !== this.props.app) {
      this.props.getClusters({ app: nextProps.app });
    }
  }

  render () {
    const { onChange, clusters } = this.props;
    const clusterList = clusters.asyncStatus === 'SUCCESS'
      ? clusters.data.map(c => ({ name: c })) : [];
    const valueOption = this.props.value && { name: this.props.value };
    return (
      <div>
        <label className={styles.label} htmlFor="cluster">Cluster</label>
        <Select
          id="cluster"
          clearable={false}
          options={clusterList}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          isLoading={clusters.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={clusters.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
        />
      </div>
    );
  }
}

const mapStateToProps = (state, ownProps) => ({
  clusters: safeTraverse(state, ['clusters', ownProps.app]) || {},
});

const mapDispatchToProps = dispatch => ({
  getClusters: params => dispatch(fetchClustersAction(params)),
});

ClusterSelectComponent.propTypes = {
  app: PropTypes.string,
  clusters: PropTypes.object.isRequired,
  getClusters: PropTypes.func.isRequired,
  onChange: PropTypes.func,
  selectedCluster: PropTypes.string,
};

export default connect(mapStateToProps, mapDispatchToProps)(ClusterSelectComponent);
